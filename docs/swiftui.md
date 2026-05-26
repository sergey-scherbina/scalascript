# SwiftUI Native Frontend Backend (v1.47)

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
    SwiftUIEmitterTest.scala       — 30 unit tests
```

### Generated Swift Package layout

```
<AppName>/
  Package.swift                          — swift-tools-version:5.9 manifest
  Sources/<AppName>/<AppName>App.swift   — @main App entry + WindowGroup
  Sources/<AppName>/ContentView.swift    — generated UI (signals + body)
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
- `sources` — three Swift files (Package.swift, App, ContentView)
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
| `RemoveSelfFromList(l)` | comment (index not available at codegen) |
| `InputChange(s)` | comment (handled by TextField binding) |
| `FetchAction(m, url, …)` | comment stub (URLSession) |
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

- **Phase 2 (planned)** — CLI integration:
  `ssc build --target mobile-ios` / `ssc compile --backend swift`,
  `Feature.SwiftUI` capability flag, toolchain check for `swiftc` / Xcode.

- **Phase 3 (planned)** — Reactive signals at runtime:
  `Combine`-based `@Published` / `ObservableObject` lowering for list mutations
  and cross-component state sharing. Requires iOS 17+ `@Observable` macro or
  iOS 14+ `ObservableObject`.

## 6. Testing strategy

- Unit: `SwiftUIEmitterTest` — 30 tests covering View cases, EventHandlers,
  style modifiers, Package.swift, App entry, platform selection, escape helpers.
- Integration (Phase 2): `ssc build --target mobile-ios` smoke test that
  invokes `swift build` on the generated package (requires Xcode on CI).

## 7. Open questions

- Should the `Combine` approach (Phase 3) target iOS 17 `@Observable` macro or
  keep `@State`-only for simplicity? `@State` is simpler and sufficient for
  self-contained single-view apps; `@Observable` is the modern approach for
  multi-view apps.
- `ForSignal` with `itemTemplate` needs a real reactive `List` lowering once
  Phase 3 lands — the current codegen emits the template once as a placeholder.
