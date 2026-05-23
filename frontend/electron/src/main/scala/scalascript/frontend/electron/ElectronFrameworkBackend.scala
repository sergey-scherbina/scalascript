package scalascript.frontend.electron

import scalascript.frontend.*
import scalascript.frontend.custom.CustomFrameworkBackend

/** Electron desktop renderer.
 *
 *  `emit()` produces the SPA content (HTML + JS) for the Chromium renderer
 *  process — identical to the `custom` backend output so existing toolkit
 *  widgets work unchanged.
 *
 *  `emitNative()` wraps that SPA content into the full five-file Electron
 *  bundle: `index.html`, `app.js`, `main.js`, `preload.js`, `package.json`.
 *
 *  Dev-run flow (`ssc run --frontend electron`):
 *    CLI writes the bundle to a temp dir, then runs `electron <dir>`.
 *
 *  Build flow (`ssc build --target desktop`):
 *    CLI writes the bundle to the output dir, then runs
 *    `npm install && npm run build` to produce the platform executable. */
final class ElectronFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "electron"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Untrack
  )

  override def jsDeps: List[JsDep] = Nil

  override def supportedPlatforms: Set[Platform] = Set(
    Platform.Desktop(DesktopOs.MacOS),
    Platform.Desktop(DesktopOs.Linux),
    Platform.Desktop(DesktopOs.Windows)
  )

  private val custom = CustomFrameworkBackend()

  /** Render the View IR to HTML + JS for the Chromium renderer process.
   *  Delegates to the custom (StaticJs) backend — same DOM primitives, no npm deps. */
  override def emit(module: FrontendModule): EmittedSpa =
    val spa   = custom.emit(module)
    val title = module.appManifest.map(_.displayName).getOrElse("ScalaScript App")
    // Replace the custom HTML shell with the Electron-specific one (CSP header).
    spa.copy(html = ElectronEmitter.indexHtml(title, css = module.extraCss))

  /** Produce the full Electron project bundle as a `NativeApp` artifact. */
  override def emitNative(
    module:   FrontendModule,
    platform: Platform
  ): Option[EmittedArtifact.NativeApp] =
    val spa         = emit(module)
    val manifest    = module.appManifest.getOrElse(AppManifest("com.example.app", "ScalaScript App", "1.0.0"))
    val displayName = manifest.displayName
    val version     = manifest.version

    val sources = Map(
      "index.html"   -> spa.html,
      "app.js"       -> spa.js,
      "main.js"      -> ElectronEmitter.mainJs(displayName),
      "preload.js"   -> ElectronEmitter.preloadJs,
      "package.json" -> ElectronEmitter.packageJson(displayName, version)
    )

    Some(EmittedArtifact.NativeApp(
      sources     = sources,
      resources   = Map.empty,
      buildScript = "npm install && npm run build",
      manifest    = manifest,
      format      = AppFormat.ElectronApp,
      target      = platform
    ))
