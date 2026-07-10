# tkv2 Raw HTML Escape Hatch

## Overview

Toolkit v2 P2 needs a bounded raw-markup escape hatch so busi migration screens
can embed a missing widget or pre-rendered HTML fragment without blocking on a
full typed component. Existing `rawText` is not enough: it lowers to
`textNode(text)` and therefore renders escaped text. This slice adds a
deliberately named `rawHtml` API for trusted markup injection in the web/custom
SPA path while keeping `rawText` escaped.

## Interface

Public API:

```scalascript
[rawHtml](std/ui/reactive.ssc)

def rawHtml(html: String): TkNode
```

Existing API unchanged:

```scalascript
def rawText(text: String): TkNode
```

`rawHtml` is a trusted escape hatch. Callers are responsible for sanitising any
user-controlled input before passing it. The implementation must not introduce
`eval`, automatic `<script>` execution, or a CSP bypass.

## Behavior

- [x] `rawText("<strong>safe</strong>")` still renders visible escaped text, not
      a nested `<strong>` element.
- [x] `rawHtml("<strong data-x=\"ok\">safe</strong>")` renders a nested
      `<strong>` element in the custom emitted SPA path.
- [x] The raw HTML sentinel does not leak as a `data-ssc-raw-html` attribute in
      the emitted DOM/HTML output.
- [x] The interpreter/frontend static renderer path treats the same sentinel as
      raw children for server-rendered UI output.
- [x] Affected toolkit-v2 conformance and `git diff --check` pass before push.

## Out of Scope

- Raw JavaScript execution or eval.
- Sanitisation of caller-provided HTML.
- React/Solid/Vue parity for this slice; toolkit-v2 production remains the
  custom emitted SPA path.
- A new `frontend.core.View` enum case. The slice should avoid broad emitter
  exhaustiveness churn unless the sentinel approach proves impossible.

## Design

Add `RawHtmlNode(html: String)` to `std.ui.nodes`, export it, and expose
`rawHtml(html: String): TkNode` from `std.ui.reactive`. `lower.ssc` lowers the
node into the existing low-level `element` primitive using a sentinel:

```scalascript
element("span",
  ["style" -> "display:contents", "data-ssc-raw-html" -> html],
  Map(),
  [])
```

Renderers that see `data-ssc-raw-html` on an element treat the attribute value
as trusted children, skip serialising the sentinel attribute itself, and ignore
normal children for that element. Other attributes, including the wrapper
`style="display:contents"`, remain intact. This preserves the existing
`frontend.core.View.Element` shape and only special-cases a toolkit-owned
attribute at rendering boundaries.

Implementation points:

- `runtime/std/ui/nodes.ssc`
- `runtime/std/ui/reactive.ssc`
- `runtime/std/ui/lower.ssc`
- `v1/runtime/backend/js/src/main/resources/scalascript/js-runtime/signals.mjs`
- `v1/runtime/backend/js/src/main/scala/scalascript/codegen/JsGen.scala`
- `frontend/custom/src/main/scala/scalascript/frontend/custom/StaticJsEmitter.scala`
- `frontend/toolkit/src/main/scala/scalascript/frontend/toolkit/Ssr.scala`
- `tests/conformance/tkv2-raw-html.ssc`

## Decisions

- **Sentinel over new View case** - chosen to keep the change narrow and avoid
  touching every frontend emitter. Rejected: adding `View.RawHtml`, which would
  require broad exhaustive-match updates across desktop/mobile emitters that are
  not part of the toolkit-v2 production web path.
- **Trusted markup only** - chosen because the migration need is embedding known
  snippets/widgets. Rejected: sanitising automatically, because there is no
  agreed policy and a false sense of safety is worse than an explicit escape
  hatch.
- **No raw-JS execution** - chosen to preserve CSP/no-eval guarantees in the
  custom SPA path. Rejected: script injection through `innerHTML`.

## Results

Implemented in `bb5342f08`.

What landed:

- `std.ui.nodes.RawHtmlNode` and public `std.ui.reactive.rawHtml(html: String): TkNode`.
- `lower.ssc` lowers `RawHtmlNode` to a `span` with `display:contents` and
  `data-ssc-raw-html`.
- The JS browser runtime, custom static emitter, and toolkit SSR stringifier
  skip the sentinel attribute and use its value as trusted children.
- `JsGen.detectCapabilities` now includes the Signals/UI runtime when the entry
  module imports any `std/ui/` module. The emitted-SPA smoke exposed this
  existing static-toolkit gap: a static page with `rawText`/`rawHtml` but no
  explicit `signal(...)` omitted `_ssc_ui_element` / `_ssc_ui_textNode`.
- Added `tests/conformance/tkv2-raw-html.ssc` and
  `examples/std-ui/raw-html-demo.ssc`.

Verification:

- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' scripts/sbtc
  "frontendCustom/compile; frontendToolkit/compile"` passed.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' scripts/sbtc
  "backendJs/compile; cli/compile"` passed after the capability fix.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' scripts/sbtc
  "frontendToolkit/testOnly scalascript.frontend.toolkit.SsrTest"` passed
  32/32, including the raw-HTML sentinel test.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' scripts/sbtc "installBin"`
  passed.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1'
  tests/conformance/run.sh --only 'std-ui-i18n,tkv2-*' --no-memo` passed
  11/11.
- `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1' bin/ssc emit-spa --frontend
  custom examples/std-ui/raw-html-demo.ssc > /tmp/tkv2-raw-html.html` emitted a
  bundle containing `_ssc_ui_element` and `_ssc_ui_textNode`; jsdom over the
  emitted HTML reported one `.ssc-page`, one nested
  `<strong data-demo="raw-html">Trusted HTML</strong>`, escaped literal text
  for `rawText`, zero `data-ssc-raw-html` DOM attributes, and zero runtime
  errors.
- `git diff --check` passed.

Follow-up recorded in `SPRINT.md`: `ssr-forsignal-duplicate-attrs-check`
tracks the pre-existing duplicated `writeAttrs(sb, attrs)` call in the
`View.ForSignal(..., itemTemplate = None)` SSR fallback branch.
