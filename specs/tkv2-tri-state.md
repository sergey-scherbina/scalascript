# tkv2-tri-state

Status: planned (2026-07-10)

## Context

`specs/ssc-toolkit-v2.md` P2-10 asks for a loading/empty/error helper for
fetched views. The existing primitives already provide the needed mechanics:
`Signal`, `eqSignal`, `computedSignal`, `showWhen`, `signalText_`, and themed
text nodes. This slice should not add a backend intrinsic or a new `TkNode`
unless the pure helper shape proves insufficient.

## API

Add `runtime/std/ui/state.ssc` with package `std.ui.state` and these exports:

- `LoadState` - data holder for `loading`, `empty`, and `error` signals.
- `loadState(loading, empty, error)` - constructor with explicit signal inputs.
- `stateName(state)` - computed `Signal[String]` returning `loading`, `error`,
  `empty`, or `ready` using the same priority as the view helper.
- `errorText(state, prefix)` - computed `Signal[String]` for displaying the
  current error with a prefix; `""` when there is no error.
- `triState(state, loadingView, emptyView, errorView, readyView)` - generic
  view selector.
- `triStateText(state, readyView, loadingText, emptyText, errorPrefix)` -
  ergonomic fetched-view helper with themed default text nodes and a reactive
  error message.

Signal contract:

- `loading`: `Signal[Boolean]`; true wins over all other states.
- `error`: `Signal[String]`; `""` means no error.
- `empty`: `Signal[Boolean]`; shown only when not loading and no error.
- ready view is shown only when not loading, no error, and not empty.

The generic selector should be equivalent to:

```scalascript
showWhen(state.loading, loadingView,
  showWhen(eqSignal(state.error, ""),
    showWhen(state.empty, emptyView, readyView),
    errorView))
```

This uses the existing positive `eqSignal(error, "")` guard instead of adding a
new negation primitive.

## Non-goals

- Do not change `fetchUrlSignal` or `fetchAction*` runtime state tracking in
  this slice.
- Do not add loading/error plumbing to `DataTableNode`; existing table helpers
  remain unchanged.
- Do not introduce a new visual design system component; callers can pass any
  `TkNode` for each state.

## Verification

- Add `tests/conformance/tkv2-tri-state.ssc` plus expected output covering all
  four states and one reactive error-text update, INT==JS.
- Add `examples/std-ui/tri-state-demo.ssc`.
- Update `README.md` and `docs/user-guide.md` for the public helper.
- Run:
  - `scripts/sbtc "installBin"`
  - `tests/conformance/run.sh --only 'tkv2-tri-state' --no-memo`
  - `git diff --check`
