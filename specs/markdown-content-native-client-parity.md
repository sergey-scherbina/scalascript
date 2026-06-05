# Markdown Content Native Client Parity

## Overview

Markdown-authored frontend content must not be limited to browser clients.
The same `.ssc` document that uses Markdown prose, heading metadata, fenced
YAML controls, `contentToolkitNode()`, selected `contentToolkitBlock(id)` /
`contentToolkitSection(id)`, `contentComponent(...)`, and `contentData(id)`
should render through native client frontends: Swing, JavaFX, and SwiftUI.

This slice is about frontend parity. The already-landed low-level
`std/content` backend exposure covers interpreter, generated JS, and generated
JVM code. Swing and JavaFX inherit the JVM side for ScalaScript code that calls
`std/content`; SwiftUI parity is achieved by lowering Markdown-authored content
to the shared toolkit/View model before the SwiftUI emitter runs, not by adding
a separate Swift Markdown/content runtime.

## Interface

No new author-facing functions are introduced. The native client parity surface
uses the existing imports:

```scalascript
[contentData](std/content.ssc)
[contentToolkitNode, contentToolkitBlock, contentToolkitSection](std/ui/content.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[serve](std/ui/primitives.ssc)
```

The same source should be able to target native clients by front-matter or CLI
frontend selection:

```yaml
frontend: swing   # or javafx / swiftui
```

```bash
ssc run-jvm --frontend swing  examples/frontend/markdown-native-controls/markdown-native-controls.ssc
ssc run-jvm --frontend javafx examples/frontend/markdown-native-controls/markdown-native-controls.ssc
ssc emit --frontend swiftui   examples/frontend/markdown-native-controls/markdown-native-controls.ssc
```

The canonical example for this slice should contain at least:

- a Markdown heading section with stable id,
- a fenced `yaml @id=... @ui=toolkit` controls block,
- structured data consumed through `contentData(id)` or component metadata,
- `contentToolkitNode()` or selected `contentToolkitSection(id)` lowering,
- text field, checkbox/toggle, and button controls, because all three native
  client families have explicit mappings for those widgets.

## Behavior

- [ ] Swing renders Markdown-authored toolkit controls as native Swing widgets:
      `JTextField`, `JCheckBox`, `JButton`, and text/layout containers.
- [ ] JavaFX renders the same Markdown-authored toolkit controls as native
      JavaFX widgets: `TextField`, `CheckBox`, `Button`, and text/layout nodes.
- [ ] SwiftUI emits the same Markdown-authored toolkit controls as SwiftUI
      declarations: `TextField`, `Toggle`, `Button`, and stack/text views.
- [ ] `contentToolkitNode()` renders the whole parsed Markdown document through
      the shared toolkit/View model before native frontend emission.
- [ ] `contentToolkitBlock(id)` and `contentToolkitSection(id)` preserve their
      selector semantics on native frontend paths: missing ids fail clearly and
      selected regions do not accidentally render sibling regions.
- [ ] Fenced `yaml @ui=toolkit` control blocks initialize native signal state
      the same way as browser/custom frontend paths for scalar defaults:
      string, boolean, integer, and floating-point values.
- [ ] `contentComponent(name)(render)` and `data=<id>` metadata remain explicit:
      registered components receive the same selected section/block metadata and
      structured data regardless of native frontend target.
- [ ] Swing and JavaFX paths can use the generated JVM `std/content` helpers in
      ordinary ScalaScript code blocks when the source is compiled through
      `ssc run-jvm`.
- [ ] SwiftUI parity does not require arbitrary ScalaScript code blocks to call
      `std/content` from generated Swift. Markdown content is consumed before
      SwiftUI emission through the existing frontend IR pipeline.
- [ ] The same example source can be checked by native emitter tests without
      hand-writing per-client markup.

## Out of Scope

- No new Markdown parser, native Markdown parser, or platform-specific content
  IR.
- No new author-facing content/toolkit functions.
- No Swift implementation of arbitrary `std/content` runtime helpers for
  generated Swift code blocks.
- No pixel-perfect visual parity between Swing, JavaFX, SwiftUI, and web
  frontends.
- No native support for toolkit widgets that the target emitter does not yet
  support. Unsupported widgets should remain existing emitter follow-ups.
- No `contentToMarkdown(...)`; that remains a later metadata/rendering slice.
- No `.sscc` / `.scir` linked artifact content lookup.

## Design

### Shared Lowering Path

Native parity must reuse the existing content and frontend pipeline:

```text
.ssc Markdown source
  -> DocumentContent snapshot
  -> std/ui content helpers
  -> TkNode / View
  -> frontend-swing / frontend-javafx / frontend-swiftui emitNative
```

Swing, JavaFX, and SwiftUI must not reparse raw Markdown independently. Any
renderer-specific behavior should be expressed as normal frontend emitter
mapping over the shared View model.

### JVM Native Clients

Swing and JavaFX are JVM frontends. For low-level metadata helpers in executable
ScalaScript code, they use the generated JVM backend support defined in
`specs/markdown-content-backend-exposure.md`. The native parity implementation
therefore only needs to prove that Markdown-authored toolkit content reaches
the Swing/JavaFX frontend modules and is emitted as native widgets.

### SwiftUI Native Client

SwiftUI is a native frontend emitter, not a ScalaScript runtime for arbitrary
code blocks. For this slice, SwiftUI parity means the evaluated frontend IR
contains the Markdown-authored controls and metadata-derived nodes before
`SwiftUIFrameworkBackend.emitNative(...)` is called. Generated Swift should only
contain normal SwiftUI declarations for the resulting View tree.

### Testing Shape

The implementation should prefer focused emitter/pathway tests over brittle
visual snapshots:

- Swing: assert generated source contains the expected Swing widget classes and
  signal/action wiring for Markdown-authored controls.
- JavaFX: assert generated source contains the expected JavaFX widget classes,
  signal table, and bindings.
- SwiftUI: assert `ContentView.swift` contains the expected SwiftUI controls and
  signal declarations for iOS and macOS where applicable.
- Shared example: run the same `.ssc` source through all three native frontend
  paths.

When toolchains are optional, tests should skip external compile/build steps if
`swift`, `swiftc`, or platform-specific JavaFX runtime pieces are unavailable,
but source emission tests should still run.

## Decisions

- **Use the shared View pipeline** - chosen because the existing frontend
  architecture already treats React, Vue, Solid, Custom, Swing, JavaFX, and
  SwiftUI as emitters over shared IR. Rejected: native-specific Markdown
  lowering, because it would fork semantics and metadata handling.
- **Treat SwiftUI as frontend emission, not `std/content` runtime execution** -
  chosen because the current SwiftUI target emits native View source rather than
  compiling arbitrary ScalaScript code blocks to Swift. Rejected: adding a Swift
  `std/content` runtime in this slice, because that is a separate language
  backend project.
- **Cover the common control subset first** - chosen because text fields,
  toggles/checkboxes, buttons, text, and stacks already have mappings in all
  three native client families. Rejected: requiring full web widget parity in
  one slice.

## Implementation Plan

1. Add a shared example under
   `examples/frontend/markdown-native-controls/markdown-native-controls.ssc`.
2. Add or extend CLI/frontend pathway tests so the example can lower through
   Swing, JavaFX, and SwiftUI without changing the source.
3. Add Swing assertions for Markdown-authored `textField`, `checkbox`, and
   `button` controls in generated source.
4. Add JavaFX assertions for the same controls in generated source.
5. Add SwiftUI assertions for the same controls in generated `ContentView.swift`.
6. Verify component/data metadata behavior is preserved by checking generated
   text or view nodes derived from `contentData(id)` / `data=<id>`.
7. Update README/user-guide with the native-client Markdown content example.

## Testing

Required verification after implementation:

```bash
cd <worktree> && sbt "frontendSwing/testOnly scalascript.frontend.swing.SwingFrameworkBackendTest" "frontendJavaFx/testOnly scalascript.frontend.javafx.JavaFxFrameworkBackendTest" "frontendSwiftUI/testOnly scalascript.frontend.swiftui.SwiftUIEmitterTest" "cli/testOnly *Markdown*"
```

The exact selectors may be narrowed during implementation, but the final result
must record the actual commands and test counts in this spec's Results section.

## Results

Spec-only slice pending implementation.
