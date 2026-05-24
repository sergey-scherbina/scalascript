# Electron Renderer

> **Status: DRAFT v0.1** — P3 implementation spec.
> Version: 0.1 — May 2026

---

## 1. Goals

- Add `electron` as a first-class `FrontendFrameworkSpi` renderer so any `.ssc`
  file that calls `serve(view, port)` can run as a desktop app with zero source
  changes.
- `ssc run --frontend electron my-app.ssc` launches a native Electron window (dev
  mode: writes bundle to temp dir, launches `electron .`).
- `ssc build --target desktop my-app.ssc` produces a distributable native binary
  via `electron-builder` (macOS `.dmg` / Windows `.exe` / Linux `.AppImage`).
- Renderer process (the Chromium window) reuses the existing `custom` JS emitter
  output — no new codegen, same DOM primitives.
- The generated bundle is a valid npm project: `npm start` / `electron .` works
  without `ssc` installed.

## 2. Non-Goals

- Hot-reload / HMR (deferred to Post-v1.0 per roadmap §19).
- Electron Forge integration (electron-builder is sufficient for P3).
- Native Node.js API surface beyond `require('electron')` in `main.js`
  (IPC, auto-updater, tray icon — post-P3 extensions).
- Multiple `BrowserWindow` instances.
- Code-signing / notarisation (manual step, documented but not automated).

## 3. Architecture

### 3.1 Module layout

```
frontend/electron/
  src/main/scala/scalascript/frontend/electron/
    ElectronFrameworkBackend.scala   ← FrontendFrameworkSpi impl
    ElectronEmitter.scala            ← main.js / package.json / preload.js generators
    ElectronBundleBuilder.scala      ← CLI/test bundle builder for .ssc project files
  src/test/scala/scalascript/frontend/electron/
    ElectronEmitterTest.scala
  src/main/resources/META-INF/services/
    scalascript.frontend.FrontendFrameworkSpi
```

### 3.2 SPI contract

```scala
final class ElectronFrameworkBackend extends FrontendFrameworkSpi:
  def name = "electron"
  def supportedPlatforms = Set(Platform.Desktop(DesktopOs.Mac),
                               Platform.Desktop(DesktopOs.Linux),
                               Platform.Desktop(DesktopOs.Windows))

  // Returns the renderer-process SPA (index.html + app.js).
  // Delegates to StaticJsEmitter (same as CustomFrameworkBackend).
  def emit(module: FrontendModule): EmittedSpa

  // Returns EmittedArtifact.NativeApp with all five files needed to
  // run/package the Electron app (see §3.3).
  override def emitNative(module: FrontendModule, platform: Platform): Option[EmittedArtifact.NativeApp]
```

### 3.3 Generated file bundle

`emitNative` produces `EmittedArtifact.NativeApp` with `sources`:

| File | Content |
|------|---------|
| `index.html` | SPA HTML from `emit()` (renderer process entry) |
| `app.js` | SPA JS from `emit()` (imperative DOM via StaticJsEmitter) |
| `main.js` | Electron main process (creates BrowserWindow, loads index.html) |
| `preload.js` | Empty preload stub (contextIsolation = true) |
| `package.json` | npm manifest: `"main": "main.js"`, electron + electron-builder deps |

`buildScript` = `npm install && npm start` (dev) / `npm install && npm run build` (dist).

### 3.4 Electron main process

```javascript
const { app, BrowserWindow } = require('electron')
const path = require('path')

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    title: '<displayName>',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })
  win.loadFile('index.html')
}

app.whenReady().then(() => {
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
```

### 3.5 CLI integration

`validFrontendNames` gains `"electron"`.

```
ssc run   --frontend electron     my-app.ssc   # write bundle to tmp, run electron
ssc run   --target desktop-electron my-app.ssc # same via target alias
ssc build --target desktop          my-app.ssc # write bundle + electron-builder
```

Default target-to-renderer mapping (§13.2 of native-platform.md):
- `--target desktop` → `--frontend electron`

Dev-run flow (`ssc run --frontend electron`):
1. Interpret `.ssc`, collect View IR from `serve(view, port)` call.
2. Call `emitNative(module, Platform.Desktop(DesktopOs.Mac))`.
3. Write all `NativeApp.sources` to a temp dir.
4. Run `electron <tmp-dir>` as a subprocess (blocking until Electron exits).

Build flow (`ssc build --target desktop`):
1. Same as dev-run through step 3.
2. Run `npm install && npm run build` inside the temp/out dir.
3. Copy the platform artifact from `dist/` to `<out-dir>/` (default: `dist/`).

### 3.6 build.sbt additions

```scala
lazy val frontendElectron = project
  .in(file("frontend/electron"))
  .dependsOn(frontendCore, frontendCustom, backendJs)
  .settings(
    name := "scalascript-frontend-electron",
    libraryDependencies ++= Seq(scalatestTest),
    Compile / scalacOptions ++= sharedScalacOptionsStrict,
    Test    / scalacOptions ++= sharedScalacOptions
  )
```

`cli` project gains `frontendElectron` as a dependency.
`stage` task copies `frontendElectron` JAR to `bin/lib/jars/`.

### 3.7 SQL in Electron

Electron renderer bundles are browser-like: generated windows load `index.html`
from `file://`, keep `nodeIntegration = false`, and cannot use Node `fs` from
renderer code. SQL support therefore follows the browser/Electron runtime path,
not the JVM or Node file-backed path.

Current `sqlite:` behavior is documented in
[`electron-sql.md`](electron-sql.md). In short, `sqlite:<path>` in the
renderer is localStorage-backed demo persistence, not a real file at `<path>`.
The persistence bridge plan is specified in
[`electron-persistence-bridge.md`](electron-persistence-bridge.md). The
`toolkit-demo` Add flow is covered by `ToolkitElectronSmokeTest`.

## 4. Migration

No breaking changes. The four existing web renderers are unchanged.
`"electron"` is added to `validFrontendNames` and the service registry.
Existing `.ssc` files that don't specify `frontend: electron` are unaffected.

## 5. Phases

| Phase | Deliverable |
|-------|-------------|
| **P3a** (this PR) | `ElectronFrameworkBackend` SPI, `emitNative` bundle generation, `ssc run --frontend electron` dev launch, `ssc build --target desktop` packaging, example, tests |
| **P3b** (future) | IPC: expose a `ssc.ipc` bridge in preload.js for renderer↔main communication |
| **P3c** (future) | Auto-updater via `electron-updater`; icon/splash from frontmatter `app:` |

## 6. Testing strategy

- Unit: `ElectronEmitterTest` — assert `main.js` shape (BrowserWindow, loadFile, quit logic),
  `package.json` fields (name, main, deps), `preload.js` is non-empty valid JS.
- Smoke: `ToolkitElectronSmokeTest` — builds `examples/frontend/toolkit-demo`
  through `ElectronBundleBuilder`, launches real Electron when available, clicks
  Add, and verifies the row through both DOM text and `fetch("/api/todos")`.
- Integration (manual): `ssc run --frontend electron examples/desktop-demo/desktop-demo.ssc`
  opens a window showing the demo UI.

## 7. Open questions

- None blocking P3a. `electron-builder` version and packaging config can be
  iterated without spec changes.
