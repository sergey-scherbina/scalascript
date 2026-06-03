# Typed Models IR

## Status

**Fully implemented across all backends — all phases landed 2026-06-02.**

| Version | What landed |
|---|---|
| `v1.66.0` | Shared IR: `ModelDef`, `ModelField`, `ModelFieldType`, `FetchJsonSignal`, `ModelView`, `ForModel`, `ModelText` in `frontend/core` |
| `v1.66.1` | SwiftUI: `emitModelStructs` → `struct X: Decodable [+ Identifiable]`, typed `@State` + `JSONDecoder`, `if let`, `ForEach`, `Text(path)` |
| `v1.66.2` | React: `useState(null)` + `r.json()`, `ModelView` → null-guard, `ForModel` → `.map`, `ModelText` → property access |
| `v1.66.3` | Vue: mount-fetch parity + `r.json()`, `v-if`/`v-for`/`{{ bs.field }}` |
| `v1.66.4` | Solid: `createSignal(null)` + `r.json()`, `<Show>`/`<For>`/`createTextNode` |
| `v1.66.5` | Custom (StaticJs): `__ssc_signals` cells, `r.json()` decode, DOM-patching |
| `v1.66.6` | Electron: delegates to Custom emitter; typed models inherited with zero extra emitter code |
| `v1.66.7` | Swing: `modelData`/`withModel` on `RuntimeState`, `modelField` dot-path, `JLabel`/`JList` via `JsonDecoder` SPI |
| `v1.66.8` | JavaFX: same plan as Swing via `JavaFxRuntime`, using JavaFX observable properties |
| `v1.66.9` | `busi-dashboard.ssc` example: BalanceSheet/TrialBalance/AuditLog, three-tab layout, `SwiftUIModelSmokeTest` |
| follow-up | `ViewTraversal.children`/`foreachDepthFirst` in `frontend/core` — React's fetch-signal collector migrated first |

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

**Landed v1.66.4.** Typed fetch stores JSON in a `createSignal(null)` accessor;
`ModelView` lowers to `<Show when={...}>`; `ForModel` lowers to `<For each={...}>`;
`ModelText` lowers to `createTextNode(String(varName.fieldPath))`.
`registerSignal` skips `FetchUrlSignal` (no `@State` analogue needed). 11 new
tests; all 46 Solid tests green.

### Custom / Static JS

**Landed v1.66.5.** `__ssc_signals` cells carry typed fetch state (null/false/false/''
for JSON variants). `r.json()` replaces `r.text()` for `CodecHint.Json` signals.
DOM-patching machinery remains unchanged.

### Electron

**Landed v1.66.6.** Electron delegates the renderer output to the Custom (StaticJs)
emitter, so typed models are inherited with zero extra emitter code. The host
bridge is unaffected.

### Swing

**Landed v1.66.7.** `modelData`/`withModel` fields added to `RuntimeState`.
`modelField` helper resolves dot-path field access. `ModelView`/`ForModel`/`ModelText`
cases added to `addTo`; async fetch goes through the `JsonDecoder` SPI and existing
`fetchDispatcher`. 7 new tests, all 19 Swing tests green.

### JavaFX

**Landed v1.66.8.** Same plan as Swing against `JavaFxRuntime`. JavaFX observable
properties used where more natural than Swing's observer surface. 7 new tests,
all 15 JavaFX tests green.

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

## Phases — all landed 2026-06-02

1. ✓ `v1.66.0-typed-models-ir` — shared IR foundation (`frontend/core`).
2. ✓ `v1.66.1-swiftui-typed-models` — SwiftUI support; `SwiftUITypedModelsTest` (16 tests).
3. ✓ `v1.66.2-react-typed-models` — React support.
4. ✓ `v1.66.3-vue-typed-models` — Vue support.
5. ✓ `v1.66.4-solid-typed-models` — Solid support; 11 new tests, 46 Solid total.
6. ✓ `v1.66.5-custom-typed-models` — Custom (StaticJs) support.
7. ✓ `v1.66.6-electron-typed-models` — Electron; no extra emitter code.
8. ✓ `v1.66.7-swing-typed-models` — Swing; 7 new tests, 19 Swing total.
9. ✓ `v1.66.8-javafx-typed-models` — JavaFX; 7 new tests, 15 JavaFX total.
10. ✓ `v1.66.9-busi-dashboard` — `busi-dashboard.ssc` example + `SwiftUIModelSmokeTest`.
11. ✓ `frontend-view-traversal-core` — `ViewTraversal.children`/`foreachDepthFirst` in `frontend/core`.

## Testing strategy

- Parser/model descriptor unit tests.
- `ModelPathResolver` tests for valid paths, invalid paths, nested lists, and
  identifying-field inference.
- Per-backend emitter tests for `RawText` and `Json` fetch signals.
- Cross-backend smoke tests once React/Vue/Solid/Custom parity lands.
- A busi dashboard smoke test that exercises real nested models and headers.
