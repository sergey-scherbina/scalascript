# JS content-toolkit natives

> Status: ✓ Landed (2026-06-11). Reported by busi (rozum seq-87, cluster-2).
> Slug: `js-content-toolkit-natives`.

## Problem

The `std/ui/content` toolkit externs — `contentToolkitNode`,
`contentToolkitBlock`, `contentToolkitSection` — were **undefined in the JS
backend**. They render authored Markdown content (sections, blocks, `toolkit:`
control links, `@ui=toolkit` YAML control trees, GFM tables, registered
components) into a TkNode tree. JvmGen and the interpreter implement them; the JS
backend emitted nothing, so JsGen's extern guard bound them to `undefined`
(`std.ui.content.contentToolkitNode` → `undefined`) and a call (e.g. busi's Rule
Pack Studio at init) threw `not callable`, blanking the screen.

The lower-level `std/content` natives (`contentDocument`, `contentBlock`,
`contentData`, `contentBind`, …) were already emitted by `emitContentRuntime`;
only the toolkit layer (the three `extern def`s in `std/ui/content.ssc`) was
missing.

## Resolution

Port JvmGen's `_ssc_tk_*` content-toolkit runtime to JS (`ContentToolkitJs`),
emitted by `JsGen.emitContentToolkitRuntime` at the end of `emitContentRuntime`,
gated by a new `contentToolkitRuntimeEnabled` flag (set when a module imports
`std/ui/content`). The port mirrors JvmGen function-for-function and builds
TkNode values as `{_type:'<Name>', <fields>}` — the shape a `.ssc` case-class
constructor compiles to — so `lower(tree, theme)` consumes them unchanged. It
reuses the existing `__ssc_content_*` helpers + `contentDocument` / `contentData`
/ `contentBind` + `_ssc_ui_signal` / `_call` / `_show`.

Binding fix: like the `std/content` natives, the three toolkit names are emitted
as **top-level functions**, so an `[..](std/ui/content.ssc)` import must bind to
the bare name (not the `undefined` namespace member the generic extern path
produces). `contentToolkitIntrinsicNames` + the import special-case handle this.

The toolkit creates signals at render time, so any `contentToolkit*` use also
triggers the JS `Signals` capability (`_ssc_ui_signal` present).

### Scope / parity

Matches JvmGen's toolkit (the closest analog to JS, both codegen backends):
markdown `toolkit:` controls (textField/checkbox/button/signalText/badge/divider
+ `enabledWhen`), `@ui=toolkit` control trees (vstack/hstack/fragment/divider/
heading/text/rawText/signalText/show/textField/checkbox/button/badge/card), GFM
tables → `TableNode`, and registered `component=<name>` rendering. The 3a/3b
`toolkit:button?action=<id>` / `toolkit:table?rows=<id>` registries (present in
the interpreter plugin, **not** in JvmGen) are a separate follow-up if needed in
the browser — it would also need adding to JvmGen for parity.

## Acceptance

- `JsGen.generate` of a module importing `std/ui/content` emits top-level
  `function contentToolkitNode/Block/Section(` and the bundle `node --check`s.
- `contentToolkitNode()` over a doc with a `toolkit:checkbox` link and an
  `@ui=toolkit` control tree, lowered + rendered, produces a `<input
  type="checkbox">`, the label, and the control-tree heading/text in the HTML.
- `examples/markdown-toolkit-links.ssc` (uses `contentToolkitSection`) emits and
  `node --check`s.

## Non-goals

- 3a/3b action/row registries in the browser (see Scope).
- Refactoring the three duplicated toolkit implementations (interpreter / JvmGen
  / JsGen) into one shared core — tracked drift risk, separate effort.
