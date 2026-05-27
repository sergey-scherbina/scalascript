package scalascript.frontend.swiftui

import scalascript.frontend.*

/** SwiftUI native frontend backend.
 *
 *  Targets `Platform.Mobile(MobileOs.iOS)` and `Platform.Desktop(DesktopOs.MacOS)`.
 *  Emits a Swift Package (Package.swift + ContentView.swift + App entry) from
 *  the framework-agnostic View IR.
 *
 *  Signal state maps to `@State private var`.  All 10 EventHandler cases are
 *  lowered to idiomatic SwiftUI (inline closures, `$binding` for inputs). */
final class SwiftUIFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "swiftui"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.StreamSignalBridge
  )

  override def jsDeps: List[JsDep] = Nil

  override def supportedPlatforms: Set[Platform] = Set(
    Platform.Mobile(MobileOs.iOS),
    Platform.Desktop(DesktopOs.MacOS)
  )

  override def emit(module: FrontendModule): EmittedSpa =
    throw UnsupportedOperationException(
      "swiftui is a native frontend; use emitNative(..., Platform.Mobile(MobileOs.iOS))"
    )

  override def emitNative(
    module:   FrontendModule,
    platform: Platform
  ): Option[EmittedArtifact.NativeApp] =
    platform match
      case Platform.Mobile(MobileOs.iOS | MobileOs.All) | Platform.Desktop(DesktopOs.MacOS | DesktopOs.All) | Platform.All =>
        val manifest = module.appManifest.getOrElse(
          AppManifest("com.example.app", "ScalaScript App", "1.0.0")
        )
        val appName   = SwiftUIEmitter.swiftIdent(manifest.displayName)
        val minIos    = manifest.minOs.get(Platform.Mobile(MobileOs.iOS)).getOrElse("17")
        val minMacos  = manifest.minOs.get(Platform.Desktop(DesktopOs.MacOS)).getOrElse("14")
        val includeIos   = platform != Platform.Desktop(DesktopOs.MacOS)
        val includeMacos = platform != Platform.Mobile(MobileOs.iOS)
        val modelSrc = SwiftUIEmitter.appModelSwift(appName, module)
        val entryView = module.components.find(_.name == module.entryPoint).map(_.body(())).getOrElse(View.Fragment(Nil))
        val hasSignals = SwiftUIEmitter.collectSignals(entryView).nonEmpty
        val baseSources = Map(
          "Package.swift"                          -> SwiftUIEmitter.packageSwift(appName, minIos, minMacos, includeIos, includeMacos),
          s"Sources/$appName/${appName}App.swift"  -> SwiftUIEmitter.appSwift(appName),
          s"Sources/$appName/ContentView.swift"    -> SwiftUIEmitter.contentView(appName, module, manifest)
        )
        val bridgeSrc  = if hasSignals then Map(s"Sources/$appName/SignalBridge.swift" -> SwiftUIEmitter.signalBridgeSwift(appName)) else Map.empty
        val allSources = baseSources ++ bridgeSrc ++ modelSrc.map(src => s"Sources/$appName/AppModel.swift" -> src)
        Some(EmittedArtifact.NativeApp(
          sources = allSources,
          resources   = Map.empty,
          buildScript = s"swift build",
          manifest    = manifest,
          format      = AppFormat.SwiftUIApp,
          target      = platform
        ))
      case _ => None
