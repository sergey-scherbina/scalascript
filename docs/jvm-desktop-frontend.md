# JVM Desktop Frontend

Status: **partially implemented** — May 2026.

Phases 1-3 have landed: `frontend-swing` is an SPI-discovered backend, the CLI
accepts `--frontend swing`, and the backend can emit a native `JFrame` source
artifact for a static toolkit subset with local signal actions. `ssc run-jvm
--frontend swing` launches that JVM desktop path through `scala-cli`. Plain
`ssc run --frontend swing` remains the interpreter path and reports that Swing
interpreter intrinsics are planned. In-process backend calls remain planned.

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
ssc run-jvm --frontend swing --transport in-process app.ssc  # parsed; diagnostic until Phase 4b
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
- `ssc run-jvm --frontend swing --transport in-process` is recognized and
  currently rejected with a Swing-specific diagnostic because the implemented
  dev path launches generated desktop sources through a nested `scala-cli`
  process, not one shared JVM process.
- `--transport in-process` should become the natural local full-stack mode for
  Swing + JVM backend once Phase 4b replaces the nested launch with a
  monolithic JVM runtime.
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
dispatch remain future work.

### Transport Integration

For monolithic apps the Swing frontend should call backend routes through
`InProcessBackendTransport`. This keeps the same request/response semantics as
REST while avoiding TCP sockets and HTTP wire parsing.

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

Status: **landed**. `ssc run-jvm --frontend swing` compiles through the JVM
backend with `frontendName=swing` and runs the generated script through
`scala-cli`. When a UI app calls `serve(view, port)`, the generated JVM UI
helper emits the native Swing bundle via `emitNative(..., Platform.Desktop())`
and launches that bundle through `scala-cli`. Plain `ssc run --frontend swing`
now preserves interpreter semantics and reports that Swing interpreter
intrinsics are not implemented yet. This still does not connect backend routes
through `InProcessBackendTransport`; that remains Phase 4.

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

Status: **in progress**. Phase 4a landed transport-option recognition for
`ssc run-jvm`: `--transport http` keeps existing behavior, and
`--transport in-process` now fails with a precise diagnostic explaining that the
current nested `scala-cli` Swing launcher is not monolithic. Phase 4b should
replace that launch model or add an equivalent same-process Swing runner before
connecting `FetchAction`/typed client calls to `InProcessBackendTransport`.

### Phase 5 — Packaging And Runtime Polish

Document JDK requirements, icon/window metadata, graceful shutdown, and optional
packaging with `jpackage`.

### Phase 6 — JavaFX / Compose Evaluation

Evaluate whether JavaFX or Compose Desktop should be implemented as additional
adapters after Swing proves the contract.

## Testing Strategy

- Unit tests for frontend selection and backend id registration.
- Golden-code tests for Swing lowering where feasible.
- Headless-safe tests for model/action generation.
- Optional GUI smoke tests guarded by headless-environment checks.
- In-process transport integration test once Phase 4 lands.

## Open Questions

- Resolved: the backend module is `frontend-swing`; the CLI/frontend SPI name
  is `swing`.
- Should `desktop-jvm` eventually default to Swing, or remain Electron + JVM
  REST unless `--frontend swing` is explicit?
- Which frontend toolkit abstraction should be the canonical source for Swing:
  current `runtime/std/ui` toolkit, lower-level frontend abstract model, or a
  new shared JVM-friendly subset?
- How much styling should the first Swing target expose before JavaFX/Compose?
- Should JavaFX support land before packaging work, or after Swing reaches
  full-stack parity for small apps?
