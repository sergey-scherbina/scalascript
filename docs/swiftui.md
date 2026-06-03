# SwiftUI Native Frontend Backend (v1.47 → v1.66)

## 1. Goals

- Emit a valid Swift Package from a `.ssc` file that uses the `frontend: swiftui` declaration.
- Target `Platform.Mobile(iOS)` and `Platform.Desktop(macOS)` from a single source file.
- Map the full View IR (`enum View[+A]`) to idiomatic SwiftUI declarations.
- Lower `ReactiveSignal[T]` to `@State private var`.
- Lower all 10 `EventHandler` cases to SwiftUI-native action expressions.
- Map the Style system to SwiftUI modifiers (padding, frame, foreground, font, cornerRadius, opacity, a11y).

## 2. Non-goals

- Hot-reload / SwiftUI Preview pipeline (requires Xcode build system; deferred to post-v1.0 per §19 of `docs/native-platform.md`).
- Server-side Swift (Vapor routes); the SwiftUI renderer is UI-only.
- Kotlin/Compose target (tracked separately as P4).
- GTK / Scala Native (P7).

## 3. Architecture

### Module layout

```
frontend/swiftui/
  src/main/scala/scalascript/frontend/swiftui/
    SwiftUIFrameworkBackend.scala  — FrontendFrameworkSpi impl
    SwiftUIEmitter.scala           — View IR → Swift source
  src/test/scala/scalascript/frontend/swiftui/
    SwiftUIEmitterTest.scala       — 41 unit tests
```

### Generated Swift Package layout

```
<AppName>/
  Package.swift                          — swift-tools-version:5.9 manifest
  Sources/<AppName>/<AppName>App.swift   — @main App entry + WindowGroup
  Sources/<AppName>/ContentView.swift    — generated UI (signals + body)
  Sources/<AppName>/AppModel.swift       — @Observable model (only when list signals exist)
```

### SPI contract

`SwiftUIFrameworkBackend` implements `FrontendFrameworkSpi`:

```scala
def name: String = "swiftui"
def supportedPlatforms: Set[Platform] = Set(
  Platform.Mobile(MobileOs.iOS),
  Platform.Desktop(DesktopOs.MacOS)
)
def emitNative(module, platform): Option[EmittedArtifact.NativeApp]
def emit(module): EmittedSpa  // throws — web SPA not supported
```

`emitNative` returns an `EmittedArtifact.NativeApp` with:
- `sources` — three or four Swift files (Package.swift, App, ContentView, and optionally AppModel)
- `buildScript` — `"swift build"`
- `format` — `AppFormat.SwiftUIApp`

### View IR → SwiftUI mapping

| View IR | SwiftUI |
|---------|---------|
| `Column` | `VStack(alignment:, spacing:) { }` |
| `Row` | `HStack(alignment:, spacing:) { }` |
| `Stack` | `ZStack { }` |
| `ScrollView` | `ScrollView(.vertical) { }` |
| `Text` | `Text("...")` |
| `SignalText` | `Text("\(signalId)")` |
| `Button` | `Button("label") { action }` |
| `TextInput` | `TextField("placeholder", text: $signalId)` |
| `Toggle` | `Toggle("label", isOn: $signalId)` |
| `Slider` | `Slider(value: $signalId, in: min...max)` |
| `Picker` | `Picker("label", selection: $signalId) { }` |
| `Image(Asset)` | `Image("name")` |
| `Image(Url)` | `AsyncImage(url: URL(string: "…")!)` |
| `Icon` | `Image(systemName: "name")` |
| `LazyList` | `List { ... }` |
| `LazyGrid` | `LazyVGrid(columns: [...]) { }` |
| `TabBar` | `TabView(selection: $cur) { ... .tabItem { } }` |
| `NavigationStack` | `NavigationStack { }` |
| `Sheet` | `.sheet(isPresented: $sig) { }` |
| `AlertDialog` | `.alert(title, isPresented: $sig) { buttons }` |
| `Form` | `Form { }` |
| `Spacer` | `Spacer()` / `Spacer(minLength: n)` |
| `Divider` | `Divider()` |
| `SafeArea` | pass-through (SwiftUI handles automatically) |
| `KeyboardAvoiding` | pass-through (SwiftUI handles automatically) |
| `Fragment` | children joined inline |
| `ForSignal(list, …, tmpl)` | `ForEach(list.indices, …) { idx in tmpl }` / `ForEach(list, …) { item in Text(item) }` |
| `Show(cond, t, f)` | evaluated at codegen time |
| `Adaptive` | picks `mobile` branch, then `desktop`, then `fallback` |

### Signal state lowering

`ReactiveSignal[T]` → `@State private var <id>: <Type> = <initial>`

Type inference:

| ScalaScript initial type | Swift type |
|---|---|
| `Boolean` | `Bool` |
| `Int` / `Long` | `Int` |
| `Double` / `Float` | `Double` |
| `String` | `String` |
| `Seq[_]` | `[String]` |

### EventHandler lowering

| EventHandler | Swift |
|---|---|
| `SetSignalLiteral(s, v)` | `s.id = v` |
| `IncrementSignal(s, n)` | `s.id += n` |
| `ToggleSignal(s)` | `s.id.toggle()` |
| `PushSignalLiteral(l, v)` | `l.id.append(v)` |
| `ClearSignalList(l)` | `l.id.removeAll()` |
| `RemoveSelfFromList(l)` | `l.id.remove(at: __idx_l.id)` inside `ForSignal`; comment otherwise |
| `InputChange(s)` | comment (handled by TextField binding) |
| `FetchAction(m, url, …)` | `Task { @MainActor in ... URLSession ... }` (GET: `data(from:)`, non-GET: `URLRequest` + `httpBody`; increments `onSuccessTick`, optionally clears `body`) |
| `Simple` / `WithEvent` | comment (closure not serializable) |

### Style modifiers

| Style field | SwiftUI modifier |
|---|---|
| `layout.padding` | `.padding(n)` / `.padding(EdgeInsets(...))` |
| `layout.width/height` | `.frame(width:, height:)` / `.frame(maxWidth: .infinity)` |
| `decoration.background` | `.background(Color(...))` |
| `text.foreground` | `.foregroundStyle(Color(...))` |
| `text.fontSize` | `.font(.system(size: n))` |
| `text.fontWeight` | `.fontWeight(.bold)` etc. |
| `text.fontStyle = Italic` | `.italic()` |
| `decoration.borderRadius` | `.cornerRadius(n)` |
| `effects.opacity` | `.opacity(v)` (only if < 1.0) |
| `a11y.label` | `.accessibilityLabel("...")` |

## 4. Migration

No breaking changes. The `swiftui` frontend backend is additive: `.ssc` files
that don't declare `frontend: swiftui` are unaffected.

## 5. Phases

- **Phase 1 ✓ Landed (2026-05-26)** — Full SwiftUI emitter:
  `SwiftUIFrameworkBackend` + `SwiftUIEmitter`, 30 unit tests covering all
  primary View cases, EventHandlers, style modifiers, Package.swift, App entry.
  `examples/frontend/ios-hello/` demonstrates a counter + name input app.
  `build.sbt` registers `frontendSwiftUI` module.

- **Phase 2 ✓ Landed (2026-05-26)** — CLI integration:
  `ssc build --target mobile-ios`, `ssc build --target desktop-macos`,
  `ssc toolchain check --target mobile-ios` (swift + Xcode detection),
  JvmGen `_ssc_ui_emit_native_platform_to_dir` + swiftui arm in `_ssc_ui_serve`.

- **Phase 3 ✓ Landed (2026-05-26)** — Reactive list lowering + `@Observable` model:
  - `ForSignal` → `ForEach(list.indices, id: \.self) { idx in ... }` (with template)
    or `ForEach(list, id: \.self) { item in Text(item) }` (without template).
  - `RemoveSelfFromList` → `list.remove(at: __idx_list)` when inside a `ForSignal`
    (index variable threaded through `ForCtx`); comment otherwise.
  - `PushSignalLiteral` / `ClearSignalList` already correct; now covered by tests.
  - When list signals exist, emits `Sources/<App>/AppModel.swift` with an
    `@Observable final class AppModel` (requires iOS 17+ / Observation framework)
    holding all list vars. Useful for cross-component sharing; ContentView keeps
    its own `@State` vars for single-view usage.
  - 11 new unit tests; total test count: 41.

- **Phase 4 (v1.65.1) ✓ Landed (2026-06-02)** — SPI registration + emit pathway:
  - Added `frontend/swiftui/src/main/resources/META-INF/services/scalascript.frontend.FrontendFrameworkSpi`
    so `SwiftUIFrameworkBackend` is discoverable via `ServiceLoader` (the file was
    missing, causing `ssc emit --frontend swiftui` to silently fall back to the
    first registered backend instead of SwiftUI).
  - `SwiftUIEmitPathwayTest` (8 tests): ServiceLoader discovery, correct name + platforms,
    iOS and macOS `emitNative` produce `Package.swift`, `<App>App.swift`,
    `ContentView.swift`, `buildScript == "swift build"`, `format == SwiftUIApp`.
  - Total test count: 57.

- **Phase 5 (v1.65.2) ✓ Landed (2026-06-02)** — FetchAction + FetchUrlSignal emit:
  - `FetchAction` emits `Task { @MainActor in ... URLSession ... }` (GET: `data(from:)`;
    non-GET: `URLRequest + httpBody`). Increments `onSuccessTick`, optionally clears body.
  - `FetchUrlSignal` emits `.task { await _load_<id>() }` + `.onChange(of: tickId) { ... }`
    modifiers on ContentView, plus `private func _load_<id>() async { URLSession... }`.
  - 5 new `SwiftUIEmitterTest` assertions. Total: 62 tests.

- **Phase 6 (v1.65.3) ✓ Landed (2026-06-02)** — Dashboard smoke test + graceful fallback:
  - Unsupported IR nodes now emit `// TODO: unsupported IR node: X\nEmptyView()` instead of
    `Text("[unsupported: X]")` — cleaner and SwiftUI-idiomatic.
  - `collectFetchSignals` expanded to walk `TabBar`, `NavigationStack`, `LazyList`,
    `LazyGrid`, `Sheet`, `SafeArea`, `KeyboardAvoiding`.
  - `examples/frontend/dashboard/dashboard.ssc` — multi-tab dashboard with FetchUrlSignal,
    ForSignal, TabBar, LazyGrid, Form, Slider, Toggle.
  - `SwiftUIDashboardSmokeTest` (13 tests): full IR coverage, `swiftc -parse` gate
    (auto-skip when `swiftc` not on PATH). Total: 75 tests.

## 6. Testing strategy

- Unit: `SwiftUIEmitterTest` — 54 tests covering View cases, EventHandlers (incl.
  FetchAction GET + POST), FetchUrlSignal, style modifiers, Package.swift, App entry,
  platform selection, ForSignal → ForEach, RemoveSelfFromList context tracking,
  AppModel generation, SignalBridge.
- Pathway: `SwiftUIEmitPathwayTest` — 8 tests; ServiceLoader discovery + full
  emit pathway for iOS and macOS (does not require Swift on CI).
- Smoke: `SwiftUIDashboardSmokeTest` — 13 tests; complex multi-tab module with all
  main IR node types; `swiftc -parse` gate (auto-skip when `swiftc` not on PATH).
- Integration (Phase 2): `ssc build --target mobile-ios` smoke test that
  invokes `swift build` on the generated package (requires Xcode on CI).

## 7. Open questions

*(All open questions resolved.)*

---

## 8. v1.65 — `ssc emit --frontend swiftui` pathway

**Status: ✓ All three phases landed 2026-06-02.**
**Spec:** this section.  **Depends on:** v1.48 ✓ (SwiftUIFrameworkBackend + SwiftUIEmitter exist)

### 8.1 Motivation

`ssc emit --frontend swiftui <file.ssc>` currently fails:

```
No FrontendFrameworkSpi impl named 'swiftui' on classpath.
```

Root cause: `frontend/swiftui/` has no
`src/main/resources/META-INF/services/scalascript.frontend.FrontendFrameworkSpi`
file.  `ServiceLoader` never discovers `SwiftUIFrameworkBackend` even though the
class is on the CLI classpath (via `cli.dependsOn(..., frontendSwiftUI, ...)`).

Secondary issue: `SwiftUIFrameworkBackend.emit()` throws
`"swiftui is a native frontend; use emitNative(...)"`.  The `ssc emit` command
calls `emit()` when the resolved platform is `Platform.Web` (the default for web
frontends).  For SwiftUI the CLI must route to `emitNative` instead.

Practical impact: the `busi` accounting app (Phase 20) had to be written as
hand-crafted Swift because the emit pathway was unavailable.

### 8.2 Goals

- `ssc emit --frontend swiftui <file.ssc>` resolves the backend and produces a
  Swift Package skeleton (Package.swift + ContentView.swift + App entry) in the
  output directory.
- `FetchAction` and `FetchUrlSignal` emit idiomatic SwiftUI async network calls
  (currently stubbed as comments in `SwiftUIEmitter`).
- `ssc emit --frontend swiftui web/dashboard.ssc` produces output that passes
  `swiftc -parse <generated>.swift` (parse-only smoke; no Xcode required).

### 8.3 Non-goals

- HMR / SwiftUI Preview pipeline (deferred to post-v1.0 per §2 above).
- watchOS / tvOS / visionOS targets (separate future milestone).
- Kotlin/Compose parity (separate milestone).

### 8.4 Architecture

#### Phase 1 — SPI registration + CLI emit routing

Two files change, no new modules:

1. **`frontend/swiftui/src/main/resources/META-INF/services/scalascript.frontend.FrontendFrameworkSpi`**
   (new file, one line):
   ```
   scalascript.frontend.swiftui.SwiftUIFrameworkBackend
   ```

2. **`SwiftUIFrameworkBackend.emit()`** — replace the `throw` with a delegation
   to `emitNative(module, Platform.All)`, serializing the resulting `NativeApp`
   sources into a single `ContentView.swift` string carried in `EmittedSpa.js`
   (a pragmatic reuse of the SPA envelope for the CLI write path).
   Alternatively — and preferably — teach the CLI `emit` command to detect
   non-`Platform.Web` backends via `supportedPlatforms` and call
   `emitForPlatform` with the backend's primary native platform.

3. **Test**: `SwiftUIEmitPathwayTest` — calls `ssc emit --frontend swiftui
   examples/frontend/ios-hello/ios-hello.ssc`, asserts output directory contains
   `ContentView.swift`, `Package.swift`, and `<AppName>App.swift`.

#### Phase 2 — `FetchAction` and `FetchUrlSignal` async emit

`SwiftUIEmitter.emitEventHandler` currently stubs `FetchAction` as a comment:

```scala
case EventHandler.FetchAction(method, url, _, _, _, _) =>
  s"""${pad}// FetchAction ${method.toUpperCase}: ${swiftString(url)}"""
```

Replace with a real emit:

```swift
Button("Submit") {
    Task { @MainActor in
        var req = URLRequest(url: URL(string: "https://api.example.com/submit")!)
        req.httpMethod = "POST"
        req.httpBody = formData.data(using: .utf8)
        _ = try? await URLSession.shared.data(for: req)
        tick += 1
    }
}
```

`FetchUrlSignal` → `@MainActor` `onAppear` + `onChange(of: tick)` block using
`URLSession.shared.data(for:)`, decoded with `JSONDecoder`.  The signal field
stores the decoded value; a `loadingState: Bool` companion `@State` var is
generated.

#### Phase 3 — `web/dashboard.ssc` smoke test

`ssc emit --frontend swiftui web/dashboard.ssc` must produce output that passes:

```bash
swiftc -parse <output-dir>/Sources/Dashboard/ContentView.swift
```

This requires `web/dashboard.ssc` to use only View IR nodes that
`SwiftUIEmitter` handles.  Any unsupported nodes encountered during the emit must
produce a `// TODO: unsupported — <NodeType>` comment rather than crashing, so
the parse test can still pass.

### 8.5 Phases

| Phase | Slug | What ships |
|-------|------|-----------|
| 1 | `v1.65.1-swiftui-spi-reg` | META-INF/services + CLI emit routing fix + `SwiftUIEmitPathwayTest` |
| 2 | `v1.65.2-swiftui-fetch-emit` | `FetchAction` + `FetchUrlSignal` → async URLSession emit |
| 3 | `v1.65.3-swiftui-dashboard-smoke` | `web/dashboard.ssc` → `swiftc -parse` green |

### 8.6 Testing strategy

- Phase 1: `SwiftUIEmitPathwayTest` — assert CLI emits expected files; existing
  `SwiftUIEmitterTest` (41 tests) must remain green.
- Phase 2: extend `SwiftUIEmitterTest` with `FetchAction` + `FetchUrlSignal`
  golden-output assertions (≥ 4 new tests).
- Phase 3: `SwiftUIDashboardSmokeTest` — calls `swiftc -parse`; skipped when
  `swift` not on PATH (macOS CI only).

---

## 9. v1.66 — Typed JSON models + DataTable

**Status: ✓ All phases landed 2026-06-02.**  Full cross-backend spec:
[`docs/typed-models-ir.md`](typed-models-ir.md).  SwiftUI-specific detail:
[`docs/swiftui-typed-models.md`](swiftui-typed-models.md).

### 9.1 What shipped

#### Model structs (`emitModelStructs`)

`FrontendModule.models: List[ModelDef]` carries `@model case class` descriptors
parsed from `.ssc` source.  `SwiftUIEmitter.emitModelStructs` emits them before
`struct ContentView`:

```swift
struct BalanceSheet: Decodable, Identifiable {
    let id: String
    let total: Double
    let lines: [AssetLine]
    let note: String?
}
struct AssetLine: Decodable, Identifiable {
    let code: String
    let amount: Double
    var id: String { code }   // computed — not named 'id'
}
```

- `Identifiable` added when any field is named `id`, `code`, `seq`, or `docId`.
- When the identifying field is not named `id`, a `var id: T { fieldName }`
  computed property is generated.
- Nested types, `List[T]` → `[T]`, `Option[T]` → `T?` all handled.
- `emitModelStructs(Nil)` returns `""` — no output when no models.

#### Typed fetch state (`FetchJsonSignal`)

`FetchJsonSignal(id, fetchUrl, tickId, modelTypeName)` extends `FetchUrlSignal`
and adds `CodecHint.Json(modelTypeName)`.  The emitter generates four `@State`
vars instead of one:

```swift
@State private var balanceSheet: BalanceSheet? = nil
@State private var balanceSheet_loading: Bool = false
@State private var balanceSheet_loaded: Bool = false
@State private var balanceSheet_error: String = ""
```

The private fetch method uses `JSONDecoder`:

```swift
private func _load_balanceSheet() async {
    balanceSheet_loading = true
    guard let _url = URL(string: "/api/balance-sheet") else { return }
    do {
        let (data, _) = try await URLSession.shared.data(from: _url)
        balanceSheet = try JSONDecoder().decode(BalanceSheet.self, from: data)
        balanceSheet_loaded = true
    } catch {
        balanceSheet_error = error.localizedDescription
    }
    balanceSheet_loading = false
}
```

#### View nodes

| IR node | Swift output |
|---|---|
| `View.ModelView(sig, "bs", template)` | `if let bs = balanceSheet { <template> }` |
| `View.ForModel("bs", "lines", "line", template)` | `ForEach(Array(bs.lines.enumerated()), id: \.offset) { _, line in <template> }` |
| `View.ModelText("bs", "total")` | `Text("\(bs.total)")` |

`ForCtx.modelItemVar` threads the Swift item variable into children of
`ForModel`, enabling `DeleteItem` to resolve the correct field path (see §9.2).

#### DataTable (`View.DataTable`)

`View.DataTable(signal, columns, actions)` emits a `List { }` with a header row
and a `ForEach` over the fetched `[[String: Any]]` array.  Columns are declared
as `FieldColumnDef(title, fieldPath, editAction?)`:

```ssc
val empTable = dataTable(
  fetchUrl = "/api/employees", tick = tick,
  columns = [fcol("Name","name"), fcol("Dept","department")],
  actions = [rowDelete("/api/employees/delete", idField="id", tick)])
```

**Editable columns** (`RowInlineEdit`): emit a `TextField` with a `Binding` over
an `_edit_<signalId>: [String: String]` buffer; `.onSubmit` fires a `PUT`/`PATCH`
request via `URLSession` and bumps the edit tick.

**Row actions** (`RowDelete`, `RowPost`, `RowLink`): emit `Button` cells that
fire `Task { @MainActor in URLSession… }` blocks using the row's dictionary
values as the request body or URL.

The full `.task`/`.onChange` fetch lifecycle is generated for DataTable signals
just as for `FetchUrlSignal`.

### 9.2 EventHandler additions

| Handler | Swift output |
|---|---|
| `EventHandler.ItemAction(method, url, bodyField, tick)` | `Task { @MainActor in }` using `item.<bodyField>` as body; bumps tick |
| `EventHandler.SetFieldToSignal(signal, fieldPath)` | `signal = fieldPath` |
| `EventHandler.DeleteItem(idField, deleteUrl, tick)` inside `ForModel` | `_req.httpBody = "\(itemVar.idField)".data(using: .utf8)` |
| `EventHandler.DeleteItem` outside `ForModel` | `// DeleteItem: no ForModel context` comment |

### 9.3 Test coverage

| Suite | Tests |
|---|---|
| `SwiftUITypedModelsTest` | 16 tests: `emitModelStructs` (8), `FetchJsonSignal` state/fetch (2), `ModelView`/`ForModel`/`ModelText` (3), `DeleteItem` in/outside `ForModel` (2), model structs before `ContentView` (1) |
| `SwiftUIModelSmokeTest` | 15 tests: busi-dashboard 3-signal 3-tab layout; `swiftc -parse` gate |
| `SwiftUIEmitterTest` | DataTable tests (12): header, `.task`, `.onChange`, edit buffer, PUT submit, re-fetch |

### 9.4 Phases

| Phase | Slug | What shipped |
|---|---|---|
| 1 | `v1.66.1-swiftui-typed-models` | `emitModelStructs`, `FetchJsonSignal`, `ModelView`, `ForModel`, `ModelText` |
| 2 | `v1.66.9-busi-dashboard` | `busi-dashboard.ssc` example + `SwiftUIModelSmokeTest` |
| 3 | `fix(swiftui/datatable)` | Inline edit correctness + 12 new DataTable tests |
| 4 | `fix(swiftui): DeleteItem` | `ForCtx.modelItemVar` threads item var; `DeleteItem` resolves `idField` |
