# Markdown Toolkit Links

## Overview

Markdown-authored frontend controls should not require a structured YAML fence
when the control is simple enough to be expressed as normal Markdown. This slice
adds a small `toolkit:` link scheme consumed by `contentToolkitNode()`,
`contentToolkitBlock(id)`, and `contentToolkitSection(id)`. The source remains
valid Markdown: without ScalaScript toolkit lowering, the controls are ordinary
links.

## Interface

Toolkit links use ordinary Markdown link syntax:

```markdown
[Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)
[Enable preview](toolkit:checkbox?signal=enabled&value=false)
[Apply](toolkit:button?signal=applied&value=true&enabledWhen=enabled)
[Team name](toolkit:signalText?signal=teamName)
[ready](toolkit:badge?variant=success)
[divider](toolkit:divider)
```

The label text is used as the control label/content when the query does not
provide one explicitly.

Supported query keys:

| Control | Required | Optional |
|---|---|---|
| `toolkit:textField` | `signal` | `label`, `value`, `disabled`, `required` |
| `toolkit:checkbox` | `signal` | `label`, `value`, `checked`, `disabled` |
| `toolkit:button` | `signal` | `label`, `value`, `enabledWhen`, `disabled` |
| `toolkit:signalText` | `signal` | |
| `toolkit:badge` | | `text`, `variant` |
| `toolkit:divider` | | |

`value` defaults by control kind:

- `textField`: empty string
- `checkbox`: `false`
- `button`: `true`

Boolean query values are `true` or `false`. URL percent-decoding applies to
query keys and values.

## Behavior

- [ ] A paragraph consisting of a single `toolkit:` link lowers to the matching
      toolkit node.
- [ ] A bullet-list item consisting of a single `toolkit:` link lowers to the
      matching toolkit node, and the list lowers to a `VStackNode` of controls.
- [ ] Multiple Markdown toolkit links that reference the same `signal` share one
      reactive signal within the selected document, section, or block lowering.
- [ ] `enabledWhen=<signal>` on a button lowers to a `ShowWhenNode` that toggles
      enabled/disabled button variants from the referenced signal.
- [ ] Non-`toolkit:` links keep the existing Markdown lowering behavior.
- [ ] The live example `examples/markdown-toolkit-links.ssc` serves a browser
      page where text field, checkbox, button, badge, and signal text are
      declared in Markdown links, not YAML.

## Out of scope

- Full form schema validation.
- Arbitrary event handlers or `fetchAction` declarations in Markdown links.
- General Markdown task-list parsing.
- Replacing `yaml @ui=toolkit`; structured YAML remains the better format for
  nested layout trees and complex controls.

## Design

The link scheme is intentionally narrow. It reuses existing `ContentInline.Link`
nodes instead of adding a parser production. Toolkit lowering recognizes a
paragraph/list item only when the whole item is a single toolkit link; mixed
prose remains normal content. Signal allocation is derived from the selected
content tree before lowering so references such as a text field and a
`signalText` link share the same `ReactiveSignal`.

## Results

Filled in after implementation.
