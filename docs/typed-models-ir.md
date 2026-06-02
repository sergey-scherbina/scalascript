# Typed Models IR

## Status

Partially implemented.  The shared IR foundation landed on 2026-06-02
(`v1.66.0-typed-models-ir`).  SwiftUI support landed in `v1.66.1`; React
support landed in `v1.66.2`; Vue support landed in `v1.66.3`. Solid, Custom,
Electron, Swing, JavaFX, and the busi dashboard example are tracked in
`WORK_QUEUE.md`.

This document is the cross-backend contract.  Backend-specific details that are
only true for SwiftUI remain in [`docs/swiftui-typed-models.md`](swiftui-typed-models.md).

## Goals

- Represent typed frontend data models once, in frontend IR, instead of adding
  ad hoc model shapes per emitter.
- Let `fetchJsonSignal` carry enough codec information for each backend to
  decode the same endpoint as typed data.
- Provide semantic view nodes for common typed-data rendering:
  `ModelView`, `ForModel`, and `ModelText`.
- Keep legacy `FetchUrlSignal` behaviour source-compatible: raw text remains
  the default codec.
- Make the `busi` dashboard expressible as `.ssc` source, without hand-written
  platform UI code.

## Non-goals

- Full compile-time JSON schema validation.
- General TypeScript generation for web backends.
- Replacing all frontend toolkit helpers in the first iteration.
- Making `ModelText` a general expression interpolation system.  It is a path
  renderer for typed model fields.

## Architecture

### Model descriptors

The parser recognizes `@model case class` and `model case class` declarations
inside parseable code blocks and appends descriptors to `Manifest.models`.

```scala
case class ModelDef(name: String, fields: List[ModelField], span: Option[Span])
case class ModelField(name: String, tpe: ModelFieldType)

enum ModelFieldType:
  case Str, IntF, DblF, BoolF
  case Nested(name: String)
  case ListOf(inner: ModelFieldType)
  case Optional(inner: ModelFieldType)
```

The current implementation parses field types from `scala.meta.Type.syntax`.
That is sufficient for `String`, `Int`, `Double`, `Boolean`, `List[T]`,
`Option[T]`, and nested model names.  A follow-up should convert directly from
`scala.meta.Type` so aliases, `Seq[T]`, nested generics, and optional/nullability
forms do not depend on string formatting.

### Fetch signals

`FetchUrlSignal` is the raw-text base signal.  `FetchJsonSignal` extends it and
returns `CodecHint.Json(modelTypeName)` from `codec`.

Backends must switch on `signal.codec`:

- `RawText`: preserve existing `r.text()` / raw `String` behaviour.
- `Json(modelTypeName)`: decode or store structured JSON data for the named
  model type.
- `FormUrlEncoded` and `Custom`: reserved for later codecs.

### View nodes

`ModelView(signal, bindingVar, template)` renders `template` only when typed data
is loaded, binding the decoded value under `bindingVar`.

`ForModel(bindingVar, fieldPath, itemVar, template)` iterates a list field
relative to `bindingVar`.

`ModelText(varName, fieldPath)` renders a scalar field path as text.

Backends should treat these as semantic nodes, not as string concatenation
helpers.  The path resolver in `frontend/core` owns validation and model-path
lookup; emitters should consume the resolved shape where possible.

Backends that lower typed fetch and typed model nodes should advertise
`Capability.TypedModels`.

## Backend lowering notes

### SwiftUI

Generate `Decodable` structs, optional `Identifiable` conformance, `@State`
typed fetch vars, `JSONDecoder`, `if let`, `ForEach`, and `Text(path)`.

### React

Use `useState(null)` for typed fetch values, `response.json()` for
`CodecHint.Json`, and render `ModelView` as a null guard.  `ForModel` lowers to
`.map(...)`; `ModelText` lowers to property access in a text node.

### Vue

Landed in `v1.66.3`. Vue maintains mount-fetch parity with React: every
`FetchUrlSignal` reachable in the view tree fetches on mount and re-fetches on
tick. Typed fetch uses `response.json()` and stores the result in a `ref`.

### Solid

Solid has the same parity requirement as Vue.  Typed fetch stores JSON in a
signal/accessor; `ModelView` lowers to `<Show>`, and `ForModel` lowers to
`<For>`.

### Custom / Static JS

The custom emitter should use the existing `__ssc_signals` runtime and DOM
patching machinery.  For typed fetch, the only decode difference is
`r.json()` instead of `r.text()`.

### Electron

Electron should verify that the web lowering and host bridge agree inside the
Electron bundle. If Electron delegates to the web emitter, the task is a focused
round-trip smoke test rather than a separate lowering implementation.

### Swing

Swing should use the model-case-class emitter and a small `JsonDecoder` hook.
The runtime fetch dispatcher remains responsible for IO; typed models are a
decode/binding layer on top of the existing dispatcher.

### JavaFX

JavaFX should follow the Swing plan against `JavaFxRuntime`, using JavaFX
observable properties where that is more natural than Swing's observer surface.

## Maintenance notes

The typed-model rollout increases the cost of duplicated frontend traversal.
React, Vue, Solid, Custom, SwiftUI, Swing, and JavaFX all need to discover
signals, fetch signals, refs, portals, lists, and now model bindings in the same
`View` tree.  Each backend having its own hand-written DFS makes parity bugs
likely.

The next infrastructure step should be a shared `ViewTraversal` /
`ViewCollectors` helper in `frontend/core`:

- one child-walking implementation for every `View` case;
- collector APIs for signals, fetch signals, fetch tables, refs, portals, and
  model bindings;
- stable ordering and duplicate-name checks;
- backend hooks for platform-specific cases that should be ignored or rejected.

This helper should not render anything.  It should only describe what is
reachable in the IR.  Backends keep their own lowering code.

## Phases

1. `v1.66.0-typed-models-ir` - landed shared IR foundation.
2. `v1.66.1-swiftui-typed-models` - landed SwiftUI support.
3. `v1.66.2-react-typed-models` - landed React support.
4. `v1.66.3-vue-typed-models` - landed Vue support.
5. `v1.66.4` through `v1.66.8` - remaining backend parity.
6. `v1.66.9-busi-dashboard` - end-to-end user-facing example.
7. `frontend-view-traversal-core` - landed 2026-06-02: `frontend/core`
   now has `ViewTraversal.children` / `foreachDepthFirst`, with adaptive
   branch options. React's fetch-signal collector is the first migrated
   backend collector; other collectors/backends remain incremental follow-ups.

## Testing strategy

- Parser/model descriptor unit tests.
- `ModelPathResolver` tests for valid paths, invalid paths, nested lists, and
  identifying-field inference.
- Per-backend emitter tests for `RawText` and `Json` fetch signals.
- Cross-backend smoke tests once React/Vue/Solid/Custom parity lands.
- A busi dashboard smoke test that exercises real nested models and headers.
