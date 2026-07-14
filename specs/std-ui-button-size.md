# std/ui button `size` — compact/regular/large button sizing

## Overview

`std/ui` (the `.ssc` declarative widget toolkit — `runtime/std/ui/*.ssc`, lowered
by `lower.ssc` to `View` and served via `serve(lower(tree, theme), port)`) has
four button constructors — `signalButton`, `actionButton`, `signalLabelButton`,
`signalActionButton` (`runtime/std/ui/input.ssc`) — and every one of them
hardcodes `padding:${sp.sm}px ${sp.md}px` and `font-size:${t.fontSize}px`
(`theme.spacing`/`theme.typography.body`) in `lower.ssc`. There is no way for a
caller to render a smaller (or larger) button — one size fits all.

This is the concrete gap behind a busi UX finding: busi wants to fit its owner
navigation bar into a 4-buttons-per-row grid on a 390px-wide phone screen
(currently 2 per row, 4 rows — wants 2 rows of 4 to cut the nav's vertical
footprint in half). At the current button font-size/padding, four
Cyrillic-labelled buttons ("Дела", "Запросы", "Воронка", "Договоры") don't
fit — the row overflows and the last button is clipped off-screen, confirmed
via a real browser screenshot. This slice adds the ability to pick a size; it
does **not** decide which busi button gets `size="sm"` or shorten any label
text — those are separate, busi-side follow-ups (out of scope here, see
below).

This is the same kind of small, additive slice as `variant`
(`specs/std-ui-button-variant.md`, commit `136e6f6bb`) — mirrors its scope
discipline and file-touch pattern closely: minimal, additive, no backend churn
beyond what's needed, `size` appended as the new LAST parameter (after
`variant`, itself appended after `disabled`).

## Interface

Public API (parameter addition only; no signature removals, no renames):

```scalascript
// runtime/std/ui/nodes.ssc — `size: String` appended as the new LAST field
case class SignalButtonNode(signal: Any,
                            value: Any,
                            label: String,
                            disabled: Boolean,
                            variant: String,
                            size: String)              extends TkNode

case class ActionButtonNode(handler: Any,
                             label: String,
                             disabled: Boolean,
                             variant: String,
                             size: String)              extends TkNode

case class SignalLabelButtonNode(signal: Any,
                                 value: Any,
                                 labelSig: Any,
                                 disabled: Boolean,
                                 variant: String,
                                 size: String)           extends TkNode

case class SignalActionButtonNode(handler: Any,
                                  labelSig: Any,
                                  disabled: Boolean,
                                  variant: String,
                                  size: String)          extends TkNode

// runtime/std/ui/input.ssc — `size: String = "md"` appended AFTER `variant`
// on every button constructor (backward compatible: existing call sites with
// no `size` argument keep today's render unchanged — "md" reproduces the
// exact pre-slice padding/font-size, byte for byte).
def signalButton(signal: Any,
                 value: Any,
                 label: String = "Submit",
                 disabled: Boolean = false,
                 variant: String = "primary",
                 size: String = "md"): TkNode

def actionButton(handler: Any,
                 label: String = "Submit",
                 disabled: Boolean = false,
                 variant: String = "primary",
                 size: String = "md"): TkNode

def signalLabelButton(signal: Any,
                      value: Any,
                      labelSig: Any,
                      disabled: Boolean = false,
                      variant: String = "primary",
                      size: String = "md"): TkNode

def signalActionButton(handler: Any,
                       labelSig: Any,
                       disabled: Boolean = false,
                       variant: String = "primary",
                       size: String = "md"): TkNode
```

- `size` — a runtime `String`, resolved to a font-size + padding pair at
  `lower()` time (same convention as `variant`'s own runtime-string ->
  themed-value resolution — a RUNTIME string resolved per re-render, not a
  compile-time enum). Accepted values: `"sm"`, `"md"`, `"lg"`. Any other
  string (a typo, an empty string, a value from some future size that
  doesn't exist yet) falls back to `"md"` — **no exception, no crash** —
  resolved in `lower.ssc`, not validated at construction time (see Design:
  "Fallback lives in `lower.ssc`, not `input.ssc`", same rule `variant`
  already established).
- Parameter position: appended as the last parameter, after `variant`, on
  all four constructors — `variant` itself was appended after `disabled`.
  Existing call sites that don't pass `size` are unaffected — they get the
  default `"md"`, i.e. today's exact padding/font-size, byte-for-byte.
  Existing call sites that already pass `variant` (several already exist in
  busi) are unaffected too — `size` is a new trailing default, not an
  inserted one.

No new `extern def`. `signalButton`/`actionButton`/`signalLabelButton`/
`signalActionButton` remain pure `.ssc` compositions of the existing
`element()` primitive (`std/ui/primitives.ssc`) — see Design.

## Behavior

- [ ] `signalButton(sig, "x", "Go")` (no `variant`/`size` arguments) lowers
      to the exact same `padding`/`font-size` CSS it produced before this
      slice — backward compatibility, verified by diffing the pre-slice and
      post-slice emitted style string for a no-`size` call.
- [ ] `actionButton(handler, "Go", false, "primary", "sm")` lowers to a
      `<button>` whose `font-size` and `padding` are both smaller than the
      `"md"` (default) render — meaningfully smaller, not a token change
      that rounds to the same pixel value.
- [ ] `actionButton(handler, "Go", false, "primary", "lg")` lowers to a
      `<button>` whose `font-size` and `padding` are both larger than `"md"`.
- [ ] Each of `"sm"`, `"md"`, `"lg"` resolves to a distinct, deterministic
      font-size/padding pair, reused from existing `Theme` tokens (see
      Design — no new magic-number literals).
- [ ] An unrecognized size string (e.g. `"bogus"`, or `""`) resolves to the
      same font-size/padding as `"md"` — no throw, no `MatchError`.
- [ ] All four constructors (`signalButton`, `actionButton`,
      `signalLabelButton`, `signalActionButton`) accept and thread `size`
      identically.
- [ ] `size` and `variant` are independent — an `sm`+`danger` button is both
      small AND red; changing one does not reset the other.
- [ ] `disabled = true` still applies the existing `opacity:0.5;
      cursor:not-allowed` overlay on top of whichever size/variant was
      picked.
- [ ] Lowering does not throw on either the `int` or `js` conformance lane
      for any accepted size or the unrecognized-string fallback case.
- [ ] `examples/frontend/button-variants/button-variants.ssc` demonstrates
      `sm`/`md`/`lg` buttons side by side (extended, not a new example — see
      Design).
- [ ] Affected `tkv2-*` / `std-ui-*` conformance cases pass before push.

## Design

### Why no new `extern def` and no `StaticJsEmitter.scala` change

Same reasoning as `variant`'s own Design section: `element(tag, attrs,
events, children): View` already handles an arbitrary `"style"` attribute
string generically — `lower.ssc` only needs to compute a *different string*
per size, not a different `View` shape. No new `View` case, no new emitter
branch, no new native intrinsic; automatically supported on both
conformance lanes (`int` and `js`) with zero backend changes.

### Reuse existing `Theme` tokens, don't invent new magic numbers

`Theme.typography` (`theme.ssc`) is a `TypographyScale(body, heading,
caption)` — and it already has exactly the three sizes this slice needs:

| button size | `TypographyScale` field | `defaultTheme` fontSize |
|---|---|---|
| `sm` | `caption` | 12px |
| `md` (current, unchanged) | `body` | 16px |
| `lg` | `heading` | 24px |

`theme.ssc`'s own comment on `caption` already says exactly what this slice
needs: *"`caption` is small text (badges/tags/pills, ~12px) — themed like
body/heading so it scales under `mobileTheme` instead of being a hardcoded
px in a primitive."* `caption` is already the toolkit's designated small-text
token (used by `Badge`/`Tag`/`Pill`); `heading` is already the designated
large-text token. Reusing them for button sizing means:

- Zero new `ColorPalette`/`TypographyScale`/`SpacingScale` fields.
- `mobileTheme`/`darkTheme` sizing stays automatic — a size button re-resolves
  under any theme the same way `variant` colors already do, with no
  size-specific theme-awareness code needed.
- `md` reproduces `theme.typography.body.fontSize` — **identical** to the
  pre-slice hardcoded `t.fontSize` — so the default-argument backward-compat
  requirement above is trivially satisfied (not just "close", byte-for-byte).

`Theme.spacing` (`SpacingScale(xs, sm, smd, md, lg, xl, xxl)`) supplies the
padding pair the same way, by shifting the (vertical, horizontal) tuple by
one position per size step:

| button size | vertical | horizontal | `defaultTheme` px |
|---|---|---|---|
| `sm` | `spacing.xs` | `spacing.sm` | `4px 8px` |
| `md` (current, unchanged) | `spacing.sm` | `spacing.md` | `8px 16px` |
| `lg` | `spacing.md` | `spacing.lg` | `16px 24px` |

`sm`'s padding (`4px 8px`) is exactly half of `md`'s (`8px 16px`) —
satisfies the task's "padding cut by something like half" target using
existing tokens, not new literals. `sm`'s font-size (12px, from `caption`)
is a 25% reduction from `md` (16px) — slightly more than the "~15-20%"
ballpark mentioned in the originating brief, but deliberately preferred over
inventing an in-between literal (e.g. `13px`) because `caption` is already
the toolkit's one existing "smaller than body" named token, used
consistently elsewhere for small text, and the combined effect (12px font +
halved padding) is what actually closes the busi 390px/4-button-row gap —
see Verification.

No token exists between `body` (16px) and `heading` (24px), so `lg` reuses
`heading` outright rather than inventing a "1.25x" literal. This is a
larger-than-"modest" jump (50%) but acceptable per task scope — `lg` exists
for API completeness/symmetry (a two-value "sm"/"md" scale would be an odd
public surface), busi does not consume it today, and it is still a reused
token, not an invented number.

### Fallback lives in `lower.ssc`, not `input.ssc`

Exactly the `variant` precedent: an unrecognized `size` string resolves to
`"md"` in `lower.ssc`, evaluated at lowering time, via a `match` with a
catch-all case. The constructors in `input.ssc` do **no** validation; they
just thread the string through to the node. This keeps the fallback rule in
one place and means a size added to `Theme` in the future only requires
touching `lower.ssc`.

### Lowering sketch (`lower.ssc`)

```scalascript
// button size (runtime String) -> font-size, in px. Reuses the existing
// TypographyScale tokens (no new theme fields): "sm" = caption (already the
// toolkit's small-text size), "md" = body (== the pre-slice hardcoded
// default, unchanged), "lg" = heading. Unknown -> "md", never a throw.
def _buttonFontSize(size: String, theme: Theme): Int = size match
  case "sm" => theme.typography.caption.fontSize
  case "lg" => theme.typography.heading.fontSize
  case _    => theme.typography.body.fontSize

// button size -> padding ("Ypx Xpx"). Reuses the existing SpacingScale
// tokens by shifting the (vertical, horizontal) pair one step per size:
// sm=(xs,sm), md=(sm,md) [== the pre-slice hardcoded default, unchanged],
// lg=(md,lg). Unknown -> "md", never a throw.
def _buttonPadding(size: String, theme: Theme): String = size match
  case "sm" => s"${theme.spacing.xs}px ${theme.spacing.sm}px"
  case "lg" => s"${theme.spacing.md}px ${theme.spacing.lg}px"
  case _    => s"${theme.spacing.sm}px ${theme.spacing.md}px"

case SignalButtonNode(sig, value, label, disabled, variant, size) =>
  val t    = theme.typography.body   // fontFamily only — unaffected by size
  val base = s"background:${_buttonColor(variant, theme)}; color:${theme.colors.onPrimary}; border:none; " +
             s"padding:${_buttonPadding(size, theme)}; font-size:${_buttonFontSize(size, theme)}px; font-family:${t.fontFamily}; " +
             s"border-radius:${theme.radii.md}px; font-weight:500; cursor:pointer"
  // ...unchanged disabled/enabled element() calls below, `base` swapped in
```

The same substitution applies identically to `ActionButtonNode`,
`SignalLabelButtonNode`, and `SignalActionButtonNode` — all four cases build
`base` the same way today and diverge only in the click-wiring and label
source, which are untouched. `font-family` stays sourced from
`theme.typography.body.fontFamily` regardless of `size` — every
`TypographyItem` in `defaultTheme`/`darkTheme`/`mobileTheme` already shares
the identical `fontFamily` string across `body`/`heading`/`caption`, so this
is a no-op simplification, not a behavior choice; `border-radius` also stays
`theme.radii.md` for every size (out of scope — the task is font-size and
padding only).

Implementation points:

- `runtime/std/ui/nodes.ssc` — `size: String` field on the four button node
  case classes, appended after `variant`.
- `runtime/std/ui/input.ssc` — `size: String = "md"` param on the four
  constructors, appended after `variant`, threaded unchanged into the node.
- `runtime/std/ui/lower.ssc` — `_buttonFontSize` + `_buttonPadding`
  resolvers (placed next to `_buttonColor`) + the four button cases' `base`
  string updated to use them.
- `examples/frontend/button-variants/button-variants.ssc` — extended with a
  size-demonstration row (not a new example — see Decisions).
- `tests/conformance/tkv2-button-size.ssc` (+
  `expected/tkv2-button-size.txt`).
- `README.md` capabilities-table row, `docs/user-guide.md` widget-catalog
  rows.

No changes needed in `FrontendIntrinsics.scala`, `StaticJsEmitter.scala`,
`CustomFrameworkBackend.scala`, `JsGen.scala`, or any JVM/Swing/SwiftUI
native-frontend lowering — same reasoning as `variant`'s own Design section.

### Same non-`input.ssc`/`nodes.ssc`/`lower.ssc` call sites `variant` needed

Adding a field to an existing node touches every other constructor of that
node, independent of the new field's *meaning*. `variant`'s slice found
three: `ContentIntrinsics.scala`, `JvmGenContentEmit.scala`, and
`std-ui-jobpanel.ssc`'s pattern-match arity. All three construct/deconstruct
these same four node types and must stay in sync with the new field count
again, this time for `size`:

- `v1/runtime/std/content-plugin/.../ContentIntrinsics.scala` —
  `signalButtonNode`/`actionButtonNode` helpers gain `"size" ->
  PluginValue.string("md")`, appended after the existing `"variant" ->
  PluginValue.string("primary")` (order matters — `instanceValue`/
  `PluginValue.orderedInstance` is array-backed, preserving field
  declaration order).
- `v1/runtime/backend/jvm/.../JvmGenContentEmit.scala` — the four
  generated-Scala-source `std.ui.nodes.SignalButtonNode(...)` call sites
  gain a 6th positional arg, `"md"`, after the existing `"primary"` 5th
  arg — a genuine compile-time requirement (the JVM-compiled
  `std.ui.nodes.SignalButtonNode` case class has no default on the
  node-level field, matching `variant`'s own precedent).
- `tests/conformance/std-ui-jobpanel.ssc` — `ActionButtonNode(_, label,
  disabled, variant)` becomes `ActionButtonNode(_, label, disabled,
  variant, size)` (+ matching `expected/std-ui-jobpanel.txt` update).
- `v1/runtime/backend/js/.../ContentToolkitJs.scala` — checked, **no**
  change needed, same reasoning `variant` already established: its
  hand-crafted `{ _type: 'ActionButtonNode', ... }` / `{ _type:
  'SignalButtonNode', ... }` JS object literals never included a `variant`
  key either (confirmed by re-reading the file for this slice) — a missing
  JS property reads as `undefined`, not a throw, and both `_buttonColor`'s
  and the new `_buttonFontSize`/`_buttonPadding`'s compiled `match`
  statements treat `undefined` the same as any other unmatched value,
  falling through to the default (`primary`/`md`) case. Content-toolkit
  buttons render exactly as they did before this slice, on every backend.

## Decisions

- **Reuse `TypographyScale.{caption,body,heading}` and shifted
  `SpacingScale` pairs, no new theme tokens** — see "Reuse existing `Theme`
  tokens" above. Rejected: a dedicated `ButtonSizeScale` or new literal
  pixel constants — out of task scope ("don't redesign the theme's type
  scale, add unrelated new typography tokens") and an unnecessary
  duplication of tokens the theme already has for exactly this purpose.
- **`size` appended as the LAST parameter on every constructor, after
  `variant`** — same backward-compatibility argument `variant` already
  made for itself: every existing positional or partially-named call site
  (including busi's, several of which already pass `variant` positionally)
  continues to compile and render identically. Rejected: inserting it
  earlier — would silently shift the meaning of any existing positional
  call passing `variant` positionally.
- **Fallback-to-`"md"` resolved in `lower.ssc`, not validated in
  `input.ssc`** — matches `variant`'s own established shape and this
  module's other runtime-string-token resolvers (`_colorOf`,
  `_statusColor`, `_linkColor`, `_buttonColor`), none of which validate at
  construction time. Rejected: throwing/asserting on an unrecognized
  string — the task brief explicitly requires "don't crash on typos".
- **A RUNTIME `String`, not a compile-time enum/sealed trait** — matches
  `variant` and every other string-token parameter already in `std/ui`.
- **Extend the existing `button-variants` example rather than create a new
  one** — `button-variants.ssc` already demonstrates every button
  constructor wired to a shared click counter; adding a `size`-demonstration
  row is a small, natural addition and keeps one canonical "button knobs"
  example instead of splitting `variant` and `size` demos across two files
  a reader has to cross-reference. Rejected: a new
  `examples/frontend/button-sizes/` directory — more files for the same
  concept, no benefit given the two features (`variant`, `size`) already
  compose orthogonally in the example's existing click-counter setup.
- **`lg` reuses `heading` outright even though it's a 50% jump, not a
  "modest" one** — see "Reuse existing `Theme` tokens" above: no
  in-between token exists, and the task explicitly deprioritizes `lg`'s
  exact scale ("busi doesn't need it today... use your own engineering
  judgment"), while still requiring the API not to look asymmetric with
  only two working values.
- **`font-family` and `border-radius` stay unaffected by `size`** — task
  scope is explicitly "smaller font-size and padding"; changing corner
  radius or typeface per size is a design decision not requested and not
  needed to solve the 390px/4-button-row problem.

## Out of Scope

- **Deciding which busi button gets `size="sm"`** — a separate, busi-side
  follow-up once this slice's pin is bumped. Not this repo's decision.
- **Shortening button label text** — a busi-side content decision, not a
  toolkit one.
- **New `Theme` tokens** (a dedicated button-size scale, new spacing/typo
  fields) — see Decisions. `sm`/`md`/`lg` reuse what already exists.
- **`checkbox`/`textField`/`select`/`selectFrom` or any other input
  primitive** — task scope is the four button constructors only.
- **Any backend other than the `custom` JS frontend
  (`StaticJsEmitter`/`CustomFrameworkBackend`) and the plain interpreter
  fallback** — see Design for why this needs zero backend-specific code
  either way (same as `variant`).
- **A `class`/raw-style escape hatch on the button constructors** — a
  separate, larger design question, not requested here (same as `variant`'s
  own Out of Scope).
- **Changing `border-radius` or `font-family` per size** — see Decisions.

## Results

What landed:

- `runtime/std/ui/nodes.ssc` — `size: String` field appended as the last
  field (after `variant`) on `SignalButtonNode`, `ActionButtonNode`,
  `SignalLabelButtonNode`, `SignalActionButtonNode`.
- `runtime/std/ui/input.ssc` — `size: String = "md"` appended as the last
  param (after `variant`) on all four constructors, threaded unchanged into
  the node.
- `runtime/std/ui/lower.ssc` — `_buttonFontSize(size, theme)` (sm→
  `typography.caption.fontSize`, lg→`typography.heading.fontSize`, else→
  `typography.body.fontSize`) and `_buttonPadding(size, theme)` (sm→`xs/sm`,
  lg→`md/lg`, else→`sm/md` from `SpacingScale`) resolvers, placed next to
  `_buttonColor`; all four button-lowering cases swap the hardcoded
  `padding:${sp.sm}px ${sp.md}px`/`font-size:${t.fontSize}px` for calls to
  the two resolvers.
- `examples/frontend/button-variants/button-variants.ssc` — extended with a
  `sizeRow` (`sm`/`md`/`lg` `actionButton`s) and a `sizeTypoBtn`
  (unrecognized-size fallback), alongside the pre-existing variant demo.
- `tests/conformance/tkv2-button-size.ssc` (+ `expected/`) — mirrors
  `tkv2-button-variant.ssc`'s TkNode-field-assertion style: asserts `size`
  threads from each of the four constructors to its node unchanged
  (including the no-`size`-arg default and an unrecognized string), and
  that `lower()` does not throw for any of them, on both `[int, js]` lanes.
- `v1/runtime/backend/interpreter-server/src/test/scala/scalascript/ButtonSizeTest.scala`
  — closes the gap `View`'s opacity leaves in the `.ssc`-level conformance
  test: runs the interpreter, extracts the real
  `frontend.core.View.Element`'s `"style"` attribute string for each size,
  and asserts the font-size/padding literals genuinely differ. Actual
  output observed (via the `defaultTheme` values baked into the assertions):

  ```
  sm:    padding:4px 8px;   font-size:12px
  md:    padding:8px 16px;  font-size:16px
  lg:    padding:16px 24px; font-size:24px
  bogus: padding:8px 16px;  font-size:16px   (== md, byte-for-byte)
  ```

  All three accepted sizes produce distinct style strings; the unrecognized
  string (`"some-typo"`) produces the identical string to `"md"` — the
  fallback is exact, not merely "doesn't crash".
- Found, fixed as a required consequence of adding a field to an existing
  node (not new scope, same class of fix `variant`'s own slice made): three
  call sites outside `input.ssc` construct/deconstruct these same node
  types and needed to stay in sync — `ContentIntrinsics.scala`'s
  `signalButtonNode`/`actionButtonNode` helpers now also set `"size" ->
  PluginValue.string("md")` (appended after `"variant"`, order matters —
  `PluginValue.orderedInstance` is array-backed); `JvmGenContentEmit.scala`'s
  four generated-Scala-source call sites now pass `"md"` as a 6th positional
  arg (a genuine compile-time requirement, same as `variant`'s own
  precedent); `tests/conformance/std-ui-jobpanel.ssc`'s
  `ActionButtonNode(_, label, disabled, variant)` pattern became
  `ActionButtonNode(_, label, disabled, variant, size)` (+ matching
  `expected/std-ui-jobpanel.txt` update). **Additionally found** (not
  enumerated in the originating task brief, but the identical class of
  required sync): `tests/conformance/tkv2-button-variant.ssc` itself
  pattern-matches on all five pre-slice node fields in five places — adding
  `size` as a 6th field broke every one of those patterns (silently, via
  the existing catch-all `case _ => "?"`, not a compile error), causing a
  real conformance regression (`tkv2-button-variant` INT lane went from 12
  expected lines to 9 `"?"` mismatches) that the sibling-suite run caught
  before push. Fixed by adding a trailing `_` for the new `size` field to
  each of the five patterns; confirmed `ContentToolkitJs.scala`'s
  hand-crafted JS object literals need **no** change (re-verified for this
  slice — they never included a `variant` key either, so the existing
  "missing property → undefined → falls through to default" reasoning
  extends unchanged to `size`).
- `README.md` capabilities-table row; `docs/user-guide.md` widget-catalog
  rows for `signalButton`/`actionButton`.

Verification:

- `scripts/sbtc "installBin"` — clean, no compile errors.
- `scripts/sbtc "backendJvm/compile"` and `scripts/sbtc "contentPlugin/compile"`
  — both clean.
- `tests/conformance/run.sh --only 'tkv2-button-size'` — **PASS [INT], PASS
  [JS]** (JVM correctly SKIP — `backends: [int, js]`).
- `tests/conformance/run.sh --only 'tkv2-*,std-ui-*' --no-memo` — **23
  passed, 0 failed** (no regressions across the sibling toolkit-v2/std-ui
  suite, including `tkv2-button-variant`'s updated pattern arity and
  `std-ui-jobpanel`'s updated pattern arity).
- `scripts/sbtc "backendInterpreterServer/testOnly scalascript.ButtonSizeTest"`
  — green; the actual per-size font-size/padding proof above.
- `scripts/sbtc "backendInterpreterServer/testOnly scalascript.ButtonVariantColorTest scalascript.StdUiSmokeTest"`
  — 3 tests, all green (no regression to the `variant` slice or the
  pre-existing `std/ui` smoke test).
- `scripts/sbtc "contentPlugin/testOnly scalascript.compiler.plugin.content.ContentPluginInterpreterTest"`
  — 29 passed, 0 failed (content-toolkit button-related tests unaffected by
  the `ContentIntrinsics.scala` field addition).
- `bin/ssc-tools emit-spa --frontend custom
  examples/frontend/button-variants/button-variants.ssc` — succeeds (exit
  0); the emitted JS bundle contains the compiled `_buttonFontSize`/
  `_buttonPadding` resolvers wired into all four button-lowering branches
  (confirmed by grepping the bundle).

Follow-ups recorded in this spec's Out-of-Scope section: busi-side
`size="sm"` wiring (which button gets `size="sm"` — not this repo's
decision, someone else bumps busi's submodule pin); shortening button label
text (busi-side content decision). No `SPRINT.md`/`BACKLOG.md` entry was
needed — this slice was claimed and completed within a single
`.work/active/std-ui-button-size.claim` cycle, closed via the claim removal
+ `CHANGELOG.md` entry in the bookkeeping commit.
