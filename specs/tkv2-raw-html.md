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

- [ ] `rawText("<strong>safe</strong>")` still renders visible escaped text, not
      a nested `<strong>` element.
- [ ] `rawHtml("<strong data-x=\"ok\">safe</strong>")` renders a nested
      `<strong>` element in the custom emitted SPA path.
- [ ] The raw HTML sentinel does not leak as a `data-ssc-raw-html` attribute in
      the emitted DOM/HTML output.
- [ ] The interpreter/frontend static renderer path treats the same sentinel as
      raw children for server-rendered UI output.
- [ ] Affected toolkit-v2 conformance and `git diff --check` pass before push.

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

Pending. Fill in repro, implementation SHA, exact verification commands, and
any follow-up after the verify step.
