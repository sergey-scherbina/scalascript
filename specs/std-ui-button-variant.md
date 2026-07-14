# std/ui button `variant` — color selection for button primitives

## Overview

`std/ui` (the `.ssc` declarative widget toolkit — `runtime/std/ui/*.ssc`, lowered
by `lower.ssc` to `View` and served via `serve(lower(tree, theme), port)`) has
four button constructors — `signalButton`, `actionButton`, `signalLabelButton`,
`signalActionButton` (`runtime/std/ui/input.ssc`) — and every one of them
hardcodes `background:${theme.colors.primary}; color:${theme.colors.onPrimary}`
in `lower.ssc`. There is no way for a caller to render a button in any other
color, even though `Theme.colors` (`runtime/std/ui/theme.ssc`, `ColorPalette`)
already defines `secondary`, `danger`, `success`, and `warning` — the palette
exists, buttons just can't select from it.

This is the concrete gap behind a busi UX audit finding: in busi's
owner-facing app, "Refresh" (no consequence) and "Prepare a real payment"
(real financial action) render in the exact same primary-blue, with no visual
hierarchy for stakes/importance. This slice adds the ability to pick a color;
it does **not** decide which busi button gets which variant — that is a
separate, busi-side follow-up (out of scope here, see below).

This is the same kind of small, additive, JS-custom-frontend-only slice as
`std-ui-select`/`selectFrom` (`specs/std-ui-select.md`) — mirrors its scope
discipline: minimal, additive, no backend churn beyond what's needed.

## Interface

Public API (parameter additions only; no signature removals, no renames):

```scalascript
// runtime/std/ui/nodes.ssc — `variant: String` appended as the new LAST field
case class SignalButtonNode(signal: Any,
                            value: Any,
                            label: String,
                            disabled: Boolean,
                            variant: String)          extends TkNode

case class ActionButtonNode(handler: Any,
                             label: String,
                             disabled: Boolean,
                             variant: String)          extends TkNode

case class SignalLabelButtonNode(signal: Any,
                                 value: Any,
                                 labelSig: Any,
                                 disabled: Boolean,
                                 variant: String)      extends TkNode

case class SignalActionButtonNode(handler: Any,
                                  labelSig: Any,
                                  disabled: Boolean,
                                  variant: String)     extends TkNode

// runtime/std/ui/input.ssc — `variant: String = "primary"` appended AFTER
// `disabled` on every button constructor (backward compatible: existing
// call sites with no `variant` argument keep today's primary-blue render
// unchanged).
def signalButton(signal: Any,
                 value: Any,
                 label: String = "Submit",
                 disabled: Boolean = false,
                 variant: String = "primary"): TkNode

def actionButton(handler: Any,
                 label: String = "Submit",
                 disabled: Boolean = false,
                 variant: String = "primary"): TkNode

def signalLabelButton(signal: Any,
                      value: Any,
                      labelSig: Any,
                      disabled: Boolean = false,
                      variant: String = "primary"): TkNode

def signalActionButton(handler: Any,
                       labelSig: Any,
                       disabled: Boolean = false,
                       variant: String = "primary"): TkNode
```

- `variant` — a runtime `String`, resolved to a themed background color at
  `lower()` time (same convention as `BadgeNode`/`TagNode`/`PillNode`'s own
  `variant: String` field + `_statusColor`/`lower.ssc`, and `AnchorNode`'s
  `variant` + `_linkColor` — a RUNTIME string resolved per re-render, not a
  compile-time enum). Accepted values: `"primary"`, `"secondary"`, `"danger"`,
  `"success"`, `"warning"` — exactly the five color names already on
  `Theme.colors` (`ColorPalette`). Any other string (a typo, an empty string,
  a value from some future variant that doesn't exist yet) falls back to
  `"primary"` — **no exception, no crash** — resolved in `lower.ssc`, not
  validated at construction time (see Decisions: "Fallback lives in
  `lower.ssc`, not `input.ssc`").
- Parameter position: appended as the last parameter, after `disabled`, on
  all four constructors. Existing call sites that don't pass `variant` are
  unaffected — they get the default `"primary"`, i.e. today's exact
  primary-blue render, byte-for-byte.

No new `extern def`. `signalButton`/`actionButton`/`signalLabelButton`/
`signalActionButton` remain pure `.ssc` compositions of the existing
`element()` primitive (`std/ui/primitives.ssc`) — see Design.

## Behavior

- [ ] `signalButton(sig, "x", "Go")` (no `variant` argument) lowers to the
      exact same `background`/`color` CSS it produced before this slice —
      backward compatibility, verified by diffing the pre-slice and
      post-slice emitted style string for a no-`variant` call.
- [ ] `actionButton(handler, "Go", false, "danger")` lowers to a `<button>`
      whose `background` is `theme.colors.danger` (not `theme.colors.primary`).
- [ ] Each of `"primary"`, `"secondary"`, `"danger"`, `"success"`, `"warning"`
      resolves to the correspondingly-named `Theme.colors` field.
- [ ] An unrecognized variant string (e.g. `"bogus"`, or `""`) resolves to
      `theme.colors.primary` — no throw, no `MatchError`.
- [ ] All four constructors (`signalButton`, `actionButton`,
      `signalLabelButton`, `signalActionButton`) accept and thread `variant`
      identically.
- [ ] `disabled = true` still applies the existing `opacity:0.5;
      cursor:not-allowed` overlay on top of whichever variant color was
      picked (the disabled styling and the variant color are independent —
      a disabled danger button is still visually a (dimmed) danger button,
      not silently reset to primary).
- [ ] Lowering does not throw on either the `int` or `js` conformance lane
      for any accepted variant or the unrecognized-string fallback case.
- [ ] `examples/frontend/button-variants/button-variants.ssc` demonstrates a
      row of buttons in different variants side by side.
- [ ] Affected `tkv2-*` / `std-ui-*` conformance cases pass before push.

## Design

### Why no new `extern def` and no `StaticJsEmitter.scala` change

Exactly the same reasoning as `std-ui-select.md` § "Why no new `extern def`":
`element(tag, attrs, events, children): View` already handles an arbitrary
`"style"` attribute string generically — `lower.ssc` only needs to compute a
*different string* per variant, not a different `View` shape. No new `View`
case, no new emitter branch, no new native intrinsic; automatically supported
on both conformance lanes (`int` and `js`) with zero backend changes.

### The on-color pairing: reuse `onPrimary`, don't add new tokens

`ColorPalette` (`theme.ssc`) has exactly one text-on-colored-background
token, `onPrimary`, paired with `primary`. There is no `onSecondary`,
`onDanger`, `onSuccess`, or `onWarning`. Two ways to get a text color for the
new variants: (a) add the four missing tokens, or (b) reuse `onPrimary`
uniformly. This slice chooses (b), for two reasons:

1. **Explicit task scope**: no new `ColorPalette` tokens — this slice adds a
   variant *selector*, not a theme redesign.
2. **Existing precedent already does exactly this in the same file.**
   `BadgeNode`, `TagNode`, and `PillNode` (`lower.ssc`) already render on
   `theme.colors.danger` / `.success` / `.warning` / `.secondary`-equivalent
   backgrounds (via `_statusColor`) with a **hardcoded** `color:#ffffff` —
   no per-variant text-color lookup at all. `onPrimary` in both `defaultTheme`
   and `darkTheme` IS `"#ffffff"`, so reusing the `onPrimary` *token* (rather
   than a second hardcoded literal) produces the identical color while
   staying theme-driven (if a future theme's `onPrimary` changes,
   `Badge`/`Tag`/`Pill` would still be hardcoded `#ffffff` but buttons would
   follow) and consistent with `SignalButtonNode`'s own pre-existing
   `color:${theme.colors.onPrimary}` line (already there for the primary
   case — this slice keeps that expression unchanged, it just stops being
   the *only* possible background pairing).

**Contrast was checked, not assumed** (WCAG relative-luminance contrast
ratio, both `defaultTheme` and `darkTheme`):

| variant | light bg | light ratio | dark bg | dark ratio |
|---|---|---|---|---|
| primary | `#2563eb` | 5.17 | `#3b82f6` | 3.68 |
| secondary | `#7c3aed` | 5.70 | `#8b5cf6` | 4.23 |
| danger | `#dc2626` | 4.83 | `#ef4444` | 3.76 |
| success | `#16a34a` | 3.30 | `#22c55e` | 2.28 |
| warning | `#d97706` | 3.19 | `#f59e0b` | 2.15 |

White-on-`success`/`warning` clears WCAG's 3:1 "UI component" floor in the
light theme but not the 4.5:1 "normal text" bar, and **fails even the 3:1
floor in the dark theme** (2.15–2.28). No single fixed color (white or a
near-black `#111827`) clears 4.5:1 against all five variants in both themes
simultaneously — that only comes from a full set of per-variant `onX`
tokens, which is out of scope. Reusing `onPrimary` (= white) is therefore an
accepted, imperfect trade — identical to the trade `Badge`/`Tag`/`Pill`
already made in this exact codebase for the exact same five colors — not a
newly-invented compromise. Flagged as a known follow-up (see Out of Scope);
not blocking, since the busi motivating case (a `danger`/`warning` button
for a real financial action) clears >=3.19 in every case measured except the
dark-theme success/warning pairing.

### Fallback lives in `lower.ssc`, not `input.ssc`

An unrecognized `variant` string resolves to `"primary"` in the same place
and the same way `_colorOf`/`_statusColor`/`_linkColor` already do it for
`StyledNode`/`BadgeNode`/`TagNode`/`PillNode`/`AnchorNode` — a `match` with a
catch-all case in `lower.ssc`, evaluated at lowering time. The constructors
in `input.ssc` do **no** validation; they just thread the string through to
the node, exactly like `BadgeNode`/`TagNode`/`PillNode`'s own `variant`
field. This keeps the fallback rule in exactly one place, matches the four
existing token-resolver functions' own established shape, and means a
variant introduced to `Theme.colors` in the future only requires touching
`lower.ssc`.

### Lowering sketch (`lower.ssc`)

```scalascript
// New resolver, same shape as _statusColor/_linkColor immediately above it.
def _buttonColor(variant: String, theme: Theme): String = variant match
  case "secondary" => theme.colors.secondary
  case "danger"    => theme.colors.danger
  case "success"   => theme.colors.success
  case "warning"   => theme.colors.warning
  case _           => theme.colors.primary   // "primary" and any unrecognized string

case SignalButtonNode(sig, value, label, disabled, variant) =>
  val sp   = theme.spacing
  val t    = theme.typography.body
  val base = s"background:${_buttonColor(variant, theme)}; color:${theme.colors.onPrimary}; border:none; " +
             s"padding:${sp.sm}px ${sp.md}px; font-size:${t.fontSize}px; font-family:${t.fontFamily}; " +
             s"border-radius:${theme.radii.md}px; font-weight:500; cursor:pointer"
  // ...unchanged disabled/enabled element() calls below, `base` swapped in
```

The same substitution (`theme.colors.primary` → `_buttonColor(variant,
theme)` in the `base` string) applies identically to `ActionButtonNode`,
`SignalLabelButtonNode`, and `SignalActionButtonNode` — all four cases build
`base` the same way today and diverge only in the click-wiring and label
source, which are untouched.

Implementation points:

- `runtime/std/ui/nodes.ssc` — `variant: String` field on the four button
  node case classes.
- `runtime/std/ui/input.ssc` — `variant: String = "primary"` param on the
  four constructors, threaded unchanged into the node.
- `runtime/std/ui/lower.ssc` — `_buttonColor` resolver + the four button
  cases' `base` string updated to use it.
- `examples/frontend/button-variants/button-variants.ssc` — new runnable
  example.
- `tests/conformance/tkv2-button-variant.ssc` (+
  `expected/tkv2-button-variant.txt`).
- `README.md` capabilities-table row, `docs/user-guide.md` §17.5-adjacent
  widget-catalog rows.

No changes needed in `FrontendIntrinsics.scala`, `StaticJsEmitter.scala`,
`CustomFrameworkBackend.scala`, `JsGen.scala`, or any JVM/Swing/SwiftUI
native-frontend lowering — same reasoning as `select`'s own Design section.

## Decisions

- **Reuse `onPrimary` for all variants' text color, no new `ColorPalette`
  tokens** — see "The on-color pairing" above. Rejected: adding
  `onSecondary`/`onDanger`/`onSuccess`/`onWarning` — out of task scope
  ("don't ... add new color tokens beyond what's already in `Theme.colors`"),
  and the codebase already has an established precedent (`Badge`/`Tag`/
  `Pill`) for the identical white-on-accent-color trade.
- **`variant` appended as the LAST parameter on every constructor, after
  `disabled`** — chosen for strict backward compatibility: every existing
  positional or partially-named call site (there are dozens across
  `examples/`, `tests/conformance/`, and busi) continues to compile and
  render identically. Rejected: inserting it earlier (e.g. after `label`) —
  would silently shift the meaning of any existing positional call passing
  `disabled` positionally.
- **Fallback-to-`primary` resolved in `lower.ssc`, not validated in
  `input.ssc`** — matches the four existing token-resolver functions
  (`_colorOf`, `_statusColor`, `_linkColor`, and the new `_buttonColor`
  itself) and `BadgeNode`/`TagNode`/`PillNode`/`AnchorNode`'s own
  `variant: String` fields, none of which validate at construction time.
  Rejected: throwing/asserting on an unrecognized string in `input.ssc` — the
  task brief explicitly requires "don't crash on typos", and every sibling
  runtime-string-variant primitive in this file already treats an unknown
  value as "fall back to the safe default", not an error.
- **A RUNTIME `String`, not a compile-time enum/sealed trait** — matches
  every other "variant" parameter already in `std/ui` (`BadgeNode.variant`,
  `TagNode.variant`, `PillNode.variant`, `AnchorNode.variant`), all resolved
  per-render against a live `Theme` via a `match` with a catch-all. A sealed
  enum would be more type-safe but inconsistent with the rest of the module
  and out of scope for a small additive slice.
- **No `class`/style escape-hatch parameter added** — the task brief
  mentions busi's investigation also found no `class`/style escape hatch on
  the button constructors, but the task itself is scoped to `variant` only;
  adding a raw style/class override is a separate, larger design question
  (would need to reconcile with `element()`'s existing `"style"` attribute
  composition) and is not requested here.

## Out of Scope

- **Deciding which busi button gets which variant** ("Prepare payment" as
  `danger`/`warning`, "Refresh" staying `primary` or going `secondary`, …) —
  a separate, busi-side follow-up once this slice's pin is bumped. Not this
  repo's decision.
- **New `ColorPalette` tokens** (`onSecondary`, `onDanger`, `onSuccess`,
  `onWarning`) — see Decisions. A follow-up if the white-on-`success`/
  `warning` contrast trade (measured above) turns out to matter in practice
  for a specific dark-theme button; not needed for this slice's scope.
- **`checkbox`/`textField` or any other input primitive** — task scope is
  the four button constructors only.
- **Any backend other than the `custom` JS frontend
  (`StaticJsEmitter`/`CustomFrameworkBackend`) and the plain interpreter
  fallback** — see Design for why this needs zero backend-specific code
  either way (same as `select`).
- **A `class`/raw-style escape hatch on the button constructors** — see
  Decisions.

## Results

_(filled in after implementation and verification)_
