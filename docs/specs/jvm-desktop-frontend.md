# JVM Desktop Frontend

Status: **phases 0–6 complete** — May 2026.

Phases 1-3 have landed: `frontend-swing` is an SPI-discovered backend, the CLI
accepts `--frontend swing`, and the backend can emit a native `JFrame` source
artifact for a static toolkit subset with local signal actions. `ssc run-jvm
--frontend swing` launches that JVM desktop path in the current JVM process via
`SwingRuntime.run(module)`. Plain `ssc run --frontend swing` remains the
interpreter path and reports that Swing interpreter intrinsics are planned.
Swing `FetchAction` handlers can dispatch to generated JVM backend routes
through generated `BackendTransport` in the same process.
`examples/frontend/swing-fullstack/` demonstrates the
no-socket path, including `fetchTable` GET/delete refresh.
`examples/frontend/swing-typed-client/` demonstrates generated typed route
clients over the same in-process dispatcher. HTTP typed clients for
Electron/browser split modes remain planned in
[`typed-route-clients.md`](typed-route-clients.md).

This document defines a JVM-hosted desktop frontend target for ScalaScript. The
first implementation target is Swing because it ships with the JDK and keeps the
bootstrap self-contained. JavaFX and Compose Desktop remain future adapters.

## Goals

- Add a desktop frontend that runs in the JVM process, without Electron, npm,
  Node.js, browser hosting, or HTTP sockets for local monolithic apps.
- Use Swing as the first adapter because it is available in the standard JDK.
- Reuse the existing frontend toolkit model where possible instead of creating
  a separate UI DSL.
- Compose with `InProcessBackendTransport` so a single JVM process can host UI
  state, frontend actions, backend routes, and server-side databases.
- Keep split/distributed REST modes available for browser, Electron, and remote
  clients.
- Provide a small runnable example before claiming the target is supported.

## Non-Goals

- Do not replace Electron. Electron remains the browser-compatible desktop
  shell for web UI and Chromium-specific behavior.
- Do not start with JavaFX or Compose Desktop. They can be better modern UI
  choices, but both add dependencies and packaging requirements that should not
  block the JDK-only proof of concept.
- Do not promise pixel-perfect parity with React/Vue/Solid. The first Swing
  target should cover a practical subset of the toolkit.
- Do not route Swing actions through HTTP for monolithic mode.
- Do not implement native installers, signing, or platform menu integration in
  the first phases.

## Architecture

The JVM desktop frontend is a frontend backend beside the existing web-oriented
frontend backends:

```text
.ssc UI source
  -> frontend toolkit / abstract model
      -> frontend-react / frontend-vue / frontend-solid / frontend-custom
      -> frontend-electron
      -> frontend-swing       # initial JVM desktop adapter
      -> frontend-javafx       # future
      -> frontend-compose      # future
```

The planned Swing runtime should run on the JVM Event Dispatch Thread (EDT).
Backend work must not block the EDT; long-running route calls should use the
same async transport boundary that HTTP clients use.

```text
Swing event handler
  -> generated action bridge
  -> BackendTransport
      -> InProcessBackendTransport  # monolithic local mode
      -> HttpBackendTransport       # optional split/distributed mode later
  -> update Swing model/components on EDT
```

### Module Layout

Planned initial module:

```text
frontend/swing/
  src/main/scala/scalascript/frontend/swing/
    SwingFrameworkBackend.scala
```

Potential backend id and aliases:

- backend id: `frontend-swing`
- CLI frontend name: `swing`
- target alias: `desktop-jvm-swing` only if a distinct target is useful later

The module should depend on `frontendCore` and JVM runtime pieces only. It
should not depend on Electron, JS backends, npm, or browser tooling.

### CLI And Front Matter

Command surface:

```bash
ssc run-jvm --frontend swing app.ssc
ssc run-jvm --frontend swing --transport http app.ssc
ssc run-jvm --frontend swing --transport in-process app.ssc
ssc run --frontend swing app.ssc                 # planned interpreter path
ssc run --frontend swing --transport in-process app.ssc  # planned
```

Planned front matter:

```yaml
frontend: swing
fullstack:
  transport: in-process
```

Default policy:

- `frontend: swing` selects the Swing frontend when the target/runtime supports
  JVM desktop execution.
- `ssc run-jvm --frontend swing --transport in-process` is accepted as the
  monolithic JVM mode foundation. The Swing UI now runs in the current JVM
  process; generated backend route dispatch from Swing actions remains planned.
- `--transport http` should remain possible later for connecting Swing to a
  separately launched backend, but it is not the first implementation target.

### Toolkit Subset

The first static subset supports:

| Toolkit concept | Swing mapping |
|---|---|
| text/label | `JLabel` |
| button/actionButton | `JButton` |
| textField | `JTextField` |
| checkbox | `JCheckBox` |
| vstack/hstack | `JPanel` with vertical/horizontal `BoxLayout` |
| spacer | rigid area |
| divider | `JSeparator` |
| scrollView | `JScrollPane` |
| basic style | padding, background, foreground, font size/weight, fixed size hints |

Table/list/modal/router equivalents remain future work after the first action
bridge works.

### Local Action Bridge

The first action bridge is local to generated Swing source. It emits a mutable
signal table plus per-signal refresh callbacks and supports:

- `SignalText` labels refreshing when a signal changes.
- `Button` actions for `SetSignalLiteral`, `IncrementSignal`, and
  `ToggleSignal`.
- `TextInput` two-way updates for `ReactiveSignal[String]`.
- `Toggle` two-way updates for `ReactiveSignal[Boolean]`.

JVM closure handlers, fetch actions, list mutation actions, and backend route
dispatch remain future work for static Swing source emission. The same-process
`SwingRuntime` path used by `ssc run-jvm --frontend swing` supports
`FetchAction` through an injected dispatcher.

### Transport Integration

For monolithic apps the Swing frontend should call backend routes through an
in-process route dispatcher. The interpreter/test harness path uses
`InProcessBackendTransport`; the generated JVM/Swing path builds a generated
`BackendTransport` over the generated JVM route registry and adapts it to
`SwingRuntime.FetchDispatcher`. Both preserve REST-shaped request/response
semantics while avoiding TCP sockets and HTTP wire parsing.

The generated Swing frontend should not call server-only databases directly.
It should use route/API boundaries, just as browser clients do. That preserves
side diagnostics and keeps a future split-mode migration possible.

### JavaFX And Compose Desktop

Future adapters:

- JavaFX: better widgets and styling than Swing, but extra dependencies are
  required on modern JDKs.
- Compose Desktop: modern declarative UI, but brings Compose/Skiko packaging and
  build-tool complexity.

Both should consume the same abstract model and transport contract after the
Swing proof of concept lands.

## Migration

No existing behavior changes in Phase 0. `frontend: electron`, React/Vue/Solid,
custom web, and split JVM REST modes keep their current behavior.

`desktop-jvm` currently means Electron + JVM REST in the implemented `ssc run`
path. Swing must therefore be selected explicitly with
`ssc run-jvm --frontend swing` for the current JVM dev path. Plain
`ssc run --frontend swing` and `frontend: swing` intentionally stay on the
interpreter path and fail with a clear diagnostic until `swing-plugin`
intrinsics land. Changing any default desktop target requires a later
compatibility decision.

## Phases

### Phase 0 — Spec And Milestone

Land this design, mark the feature as planned, and add the backlog.

### Phase 1 — Swing Backend Skeleton

Add `frontend-swing` module, backend registration, CLI/frontend-name plumbing,
and a minimal `JFrame` runtime. The first runnable example can show static text
or a button without backend calls.

Status: **landed**. The backend emits an `EmittedArtifact.NativeApp` with
`AppFormat.SwingApp` and a generated `src/main/scala/Main.scala` source file.
The example is [`examples/frontend/swing-hello/swing-hello.ssc`](../examples/frontend/swing-hello/swing-hello.ssc).

### Phase 2 — Toolkit Subset

Lower a small toolkit subset to Swing: label/text, button, text field,
checkbox, vstack/hstack, and simple styling/layout defaults.

Status: **landed**. The emitted Swing source now handles `Text`, `TextNode`,
`SignalText` snapshots, `Button`, `TextInput`, `Toggle`, `Column`, `Row`,
`Spacer`, `Divider`, `ScrollView`, `Fragment`, `Show`, `For`, `Styled`, and
desktop `Adaptive` branches. Basic style lowering covers padding, background,
foreground, font size/weight, fixed size hints, borders, and accessibility
labels.

### Phase 3 — Action Bridge

Wire Swing events to generated ScalaScript actions and update UI state on the
EDT. Add counter/todo-style examples.

Status: **landed** for local signal actions. Generated Swing source now emits
`setSignal`, `incrementSignal`, `toggleSignal`, `bindSignal`, and signal value
helpers; `SignalText`, `TextInput`, and `Toggle` refresh from the signal table.
Backend-route actions and transport dispatch remain Phase 4 work.

### Phase 3b — Swing JVM Dev Run

Launch generated Swing desktop sources from `ssc run-jvm --frontend swing`.

Status: **landed, superseded by Phase 4b**. `ssc run-jvm --frontend swing`
compiles through the JVM backend with `frontendName=swing`. Phase 3b originally
emitted a native Swing bundle via `emitNative(..., Platform.Desktop())` and
launched it through nested `scala-cli`; Phase 4b replaced that launcher with
same-process `SwingRuntime.run(module)`. Plain `ssc run --frontend swing` still
preserves interpreter semantics and reports that Swing interpreter intrinsics
are not implemented yet. Backend route dispatch remains Phase 4 work.

### Phase 3c — Swing Interpreter Plugin Skeleton

Create the standard-library plugin module that will own Swing interpreter
intrinsics.

Status: **landed**. `runtime/std/swing-plugin` registers
`scalascript.std.swing` as an intrinsic-provider plugin with an intentionally
empty intrinsic table. Future interpreter work should extend this plugin rather
than adding Swing intrinsics to core or to the interpreter backend directly.

### Phase 4 — In-Process Full-Stack Mode

Connect Swing frontend actions to backend routes through
`InProcessBackendTransport`. Add a full-stack example that writes on the JVM
backend and updates the desktop UI without opening an HTTP socket.

Status: **complete (2026-05-26)**. All sub-phases landed. Design decision on
implementation sharing: the generated JVM path (`_ssc_ui_backend_transport`
inlined into generated code) and `InProcessBackendTransport` serve the same
logical role but intentionally do NOT share a concrete implementation.  The
generated path dispatches directly into the compiled `_routes` table; the
interpreter path dispatches through `InterpreterHttpHandler` over interpreted
IR.  Both implement `BackendTransport` — that is the right level of sharing.
Coupling them at the implementation level would pull the codegen module into
a dependency on the interpreter, reversing the module dependency direction
without benefit.

### Phase 5 — Packaging And Runtime Polish ✓ Landed (2026-05-26)

JDK requirements, window icon, graceful shutdown, and packaging documentation.

**JDK requirements.** Swing is part of the standard JDK. Any JDK 11+ that
`scala-cli` resolves is sufficient.  `jpackage` (for platform-native bundles)
requires JDK 14+; the tool ships with the JDK and does not need a separate
install.

**Window icon.** Front matter `app-icon: path/to/icon.png` sets the window
icon.  JvmGen reads the value from `manifest.raw` and emits it as
`iconPath = Some(...)` in the generated `SwingRuntime.Options`.  At runtime
`SwingRuntime.frameFor` loads the image via `ImageIcon` and calls
`frame.setIconImage`.  Icon-load errors are silently ignored so an absent
file does not crash the app.

**Graceful shutdown.** `SwingRuntime.Options` now accepts
`onShutdown: Option[() => Unit]`.  When set, `frameFor` replaces
`EXIT_ON_CLOSE` with a `WindowAdapter` that calls the hook before disposing
the frame.  This is the correct integration point for stopping background
work (HTTP servers, actor systems, database pools) on window close.

**jpackage packaging.** After `ssc run-jvm` confirms the app works, use
`scala-cli package --assembly app.ssc -o app.jar` to produce a fat JAR, then
`jpackage --input . --main-jar app.jar --name MyApp --type dmg|msi|deb` to
wrap it in a platform installer.  Automated `ssc build --target desktop-jvm`
remains future work (Phase 5b).

### Phase 6 — JavaFX / Compose Evaluation ✓ Landed (2026-05-26)

Evaluate whether JavaFX or Compose Desktop should be implemented as additional
adapters after Swing proves the contract.  Now that Phases 1–5 have landed, the
contract is concrete:

- **SPI entry point** — extend `FrontendFrameworkSpi`, override `emitNative`.
- **Code generation shape** — produce Scala source compiled by `scala-cli run .`.
- **State model** — mutable signal table (`Map[String, Any]`) + bindings map.
- **Transport boundary** — implement `BackendTransport`; in-process path wires
  into generated route registry without HTTP.
- **Packaging** — fat JAR via `scala-cli package --assembly`, then `jpackage`.

#### JavaFX

**Feasibility: viable — recommended as the next adapter.**

JavaFX has been distributed separately from the JDK since Java 11.  The OpenJFX
artifacts (`org.openjfx:javafx-controls`, `javafx-fxml`, etc.) are published on
Maven Central with platform classifiers (`mac`, `linux`, `win`).  `scala-cli`
resolves them normally via `//> using dep org.openjfx:javafx-controls:21.0.5`.

The emitter shape mirrors Swing:

| Swing widget        | JavaFX equivalent            |
|---------------------|------------------------------|
| `JLabel`            | `Label`                      |
| `JButton`           | `Button`                     |
| `JTextField`        | `TextField`                  |
| `JPasswordField`    | `PasswordField`              |
| `JTextArea`         | `TextArea`                   |
| `JCheckBox`         | `CheckBox`                   |
| `JPanel` / BoxLayout Y | `VBox`                   |
| `JPanel` / BoxLayout X | `HBox`                   |
| `JScrollPane`       | `ScrollPane`                 |
| `JSeparator`        | `Separator`                  |
| `Box.createRigidArea` | `Region` with `setPrefSize` |

Threading is analogous: `Platform.runLater { ... }` replaces
`SwingUtilities.invokeLater { ... }`.

**Advantages over Swing:**
- Native CSS styling: `node.setStyle("-fx-font-size: 14px; -fx-background-color: #fff;")`.
  All `Style` fields map cleanly without bespoke `emitForeground`/`emitBorder`
  helpers — a single `setStyle(cssString)` call covers everything.
- Better default look on all platforms (HiDPI-aware, anti-aliased).
- `WebView` node is available for hybrid HTML/native content without Electron.
- Property-binding system (`StringProperty`, `BooleanProperty`) allows cleaner
  signal wiring than the manual refresh callbacks in the current Swing emitter.

**Drawbacks:**
- Extra JARs (~25 MB per platform).  Users must have OpenJFX on the classpath or
  rely on `scala-cli`'s dependency resolution; the generated source must carry
  the `//> using dep` directives.
- `Application.launch()` requires a public no-arg constructor and takes over
  `main`.  The generated entry point must subclass `javafx.application.Application`
  and override `start(Stage primaryStage)`.  This is more boilerplate than Swing
  but is entirely emitter-side; the `.ssc` author sees nothing of it.
- `jpackage` on macOS requires `--mac-package-name` and the JavaFX `--module-path`
  flag, adding packaging complexity.

**Implementation path (draft phases for a future v1.46+ milestone):**

1. Add `frontend/javafx` module; `JavaFxFrameworkBackend extends FrontendFrameworkSpi`.
2. `JavaFxEmitter` generates a `//> using dep org.openjfx:...` source with an
   `App extends Application` class.  Signal table becomes JavaFX `SimpleStringProperty` /
   `SimpleBooleanProperty` + listeners — no manual binding map needed.
3. Add `AppFormat.JavaFxApp`; plumb `--frontend javafx` through CLI and JvmGen.
4. Example: `examples/frontend/javafx-hello/`.
5. CSS style emitter: one `emitStyle(node, style): String` method renders the full
   `Style` case class to a single inline `-fx-*` stylesheet string.
6. Conformance: adapt the existing Swing golden-code tests to JavaFX.

**Decision: implement JavaFX after Swing styling parity is reached.**  The
dependency story is manageable, the widget mapping is nearly 1:1, and CSS styling
is a significant UX improvement.  Track under a future `v1.47 — JavaFX Desktop
Frontend` milestone.

#### Compose Desktop

**Feasibility: blocked — defer until toolchain support lands.**

Compose Desktop (JetBrains Compose Multiplatform) requires:

1. **Kotlin** — Compose is a Kotlin library.  The `@Composable` annotation is
   processed by a Kotlin compiler plugin (`org.jetbrains.compose`).  There is no
   Java/Scala equivalent.
2. **Gradle** — the Compose Multiplatform Gradle plugin manages Skiko (a
   Skia-based native rendering library, ~50 MB per platform) and the Kotlin
   compiler plugin.  `scala-cli` cannot drive this pipeline today.

The fundamental blocker is that ScalaScript currently generates Scala source
compiled by `scala-cli`.  Compose Desktop requires generating Kotlin source and
driving a Gradle build.  That is a new code-generation pipeline — not an
incremental emitter addition.

**Advantages (for the record):**
- Truly declarative, composable UI that maps cleanly to `View[?]` ADT.
- `mutableStateOf(...)` / `remember { ... }` maps 1:1 to ReactiveSignal semantics.
- iOS and Android reuse the same composable components (Compose Multiplatform).
- HiDPI, animation, and modern theming built-in.

**Conditions for revisiting:**
- `scala-cli` gains support for mixed Scala/Kotlin projects with compiler plugins
  (tracked upstream), **or**
- ScalaScript adds a Kotlin/Gradle code generation path as a first-class output
  mode (large separate effort), **or**
- JetBrains ships a Java/Scala-accessible Compose runtime that doesn't require
  the Kotlin compiler plugin (unlikely near-term).

**Decision: defer Compose Desktop.**  Add a `DEFERRED` note in the Compose
sub-section of the open questions and revisit when the toolchain situation
changes.  No implementation work until a `scala-cli`-compatible path exists.

#### Summary

| Adapter          | Status      | Blocker                             | Recommended next? |
|------------------|-------------|-------------------------------------|-------------------|
| Swing            | ✓ Landed    | —                                   | —                 |
| JavaFX           | Planned     | None (managed by `scala-cli` deps)  | Yes               |
| Compose Desktop  | Deferred    | Requires Kotlin + Gradle pipeline   | No                |

## Testing Strategy

- Unit tests for frontend selection and backend id registration.
- Golden-code tests for Swing lowering where feasible.
- Headless-safe tests for model/action generation.
- Optional GUI smoke tests guarded by headless-environment checks.
- In-process transport integration test once Phase 4 lands.

## Open Questions

- Resolved: the backend module is `frontend-swing`; the CLI/frontend SPI name
  is `swing`.
- Resolved: JavaFX is the recommended next adapter (see Phase 6 evaluation).
  Track under a future `v1.47 — JavaFX Desktop Frontend` milestone.
- Resolved: Compose Desktop is deferred until `scala-cli` supports
  Kotlin + Compose compiler plugins, or ScalaScript adds a Kotlin/Gradle
  code-generation pipeline.
- Should `desktop-jvm` eventually default to Swing, or remain Electron + JVM
  REST unless `--frontend swing` is explicit?
- Which frontend toolkit abstraction should be the canonical source for Swing:
  current `runtime/std/ui` toolkit, lower-level frontend abstract model, or a
  new shared JVM-friendly subset?
