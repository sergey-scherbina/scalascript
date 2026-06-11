# Hash-tolerant `eqSignal` for browser routing

> Status: ✓ Landed (2026-06-11). Reported by busi (rozum seq-94).
> Slug: `js-routing-showsignal-hash`.

## Problem

Hand-rolled hash routing kept the matched route hidden in the browser:

```scalascript
val routeSig = hashSignal()
showSignal(eqSignal(routeSig, "#/a"), pageA, emptyView)   // pageA hidden at #/a
```

At `window.location.hash === "#/a"` the `pageA` branch rendered with
`display:none` while the fallback showed — even though the hash "matched".

## Root cause

`_ssc_ui_currentHash()` / `_ssc_ui_hashSignal()` **strip the leading `#`**: for
`#/a` the signal value is `"/a"`. This is the convention `hashRouter` relies on
(`route("/a")` paths are compared against the stripped hash). But a user routing
by hand compares against the **URL form** (`"#/a"`) they wrote in an `<a href>`,
so `eqSignal("/a", "#/a")` was always `false`.

The signal subscription / recompute machinery was correct (verified: comparing
against the stripped `"/a"` matched and toggled fine). It was purely a
compare-form mismatch — `#/a` (what you see / write) vs `/a` (what `hashSignal`
stores).

## Resolution

Make `_ssc_ui_eqSignal` **hash-tolerant**: normalise a single leading `#` on both
operands before comparing (`_ssc_ui_hashEq`). `"#/a"` and `"/a"` then compare
equal, so both the URL form and the stripped form work — for hand-rolled routing
and `hashRouter` alike.

This only ever turns a `"#x"`-vs-`"x"` mismatch into a match; it never breaks an
already-equal comparison. Non-string and non-`#` values pass through unchanged,
so TabBar keys (`"bank"`/`"fx"`) and plain route paths (`"/home"`) are
unaffected.

## Acceptance

- `eqSignal(hashSignal(), "#/a")` is `true` at `hash="#/a"` (and `eqSignal(…, "/a")`
  remains `true`); a non-matching route stays `false`.
- The matched `showSignal` branch is visible (`display:contents`) on mount; the
  fallback is hidden.
- Plain-key equality (`eqSignal(Signal("bank"), "bank")`) and the existing
  hash-routes / show-guard behaviour are unchanged.

## Non-goals

- Changing what `hashSignal()` stores (it stays stripped — `hashRouter` and
  existing code depend on it). The tolerance lives in the comparison.
