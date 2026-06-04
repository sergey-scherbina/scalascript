package scalascript.frontend

import scalascript.ast.ModelDef

/** A pluggable frontend-framework backend.
 *
 *  Four production web impls: `custom`, `react`, `solid`, `vue`.
 *  Native backends (`electron`, `react-native`, `compose`, `swiftui`,
 *  `gtk`, `javafx`) are added in P3–P7; they override `emitNative` and
 *  `supportedPlatforms`.
 *
 *  See `specs/native-platform.md` §9 for the full SPI contract. */
trait FrontendFrameworkSpi:

  def name: String

  def capabilities: Set[Capability]

  def jsDeps: List[JsDep]

  /** Emit a web SPA from the framework-agnostic IR. */
  def emit(module: FrontendModule): EmittedSpa

  /** Platforms this backend targets.  Web renderers return `Set(Platform.Web)` (default). */
  def supportedPlatforms: Set[Platform] = Set(Platform.Web)

  /** Emit a native app artifact.  Returns `None` for web-only backends (the default). */
  def emitNative(module: FrontendModule, platform: Platform): Option[EmittedArtifact.NativeApp] = None

object FrontendFrameworkSpi:
  extension (spi: FrontendFrameworkSpi)
    def emitForPlatform(module: FrontendModule, platform: Platform): EmittedArtifact =
      platform match
        case Platform.Web => EmittedArtifact.Spa(spi.emit(module))
        case p            => spi.emitNative(module, p).getOrElse(
          throw UnsupportedOperationException(s"${spi.name} does not support platform $p")
        )

/** Emitted artifact — either a web SPA or a native app bundle. */
enum EmittedArtifact:
  case Spa(spa: EmittedSpa)
  case NativeApp(
    sources:     Map[String, String],
    resources:   Map[String, Array[Byte]],
    buildScript: String,
    manifest:    AppManifest,
    format:      AppFormat,
    target:      Platform
  )

final case class JsDep(npmName: String, version: String, importPath: String)

final case class EmittedSpa(js: String, html: String, css: String)

/** Application bundle metadata for native builds. */
final case class AppManifest(
  bundleId:    String,
  displayName: String,
  version:     String,
  minOs:       Map[Platform, String] = Map.empty
)

/** Framework-agnostic IR consumed by backends.
 *
 *  `targetPlatform` and `appManifest` are new in v0.3 (native platform support).
 *  `models` carries typed model descriptors from `@model case class` declarations
 *  (v1.66); backends with `Capability.TypedModels` consume this to emit data structs
 *  and typed fetch wiring.  Default `Nil` keeps all existing construction sites
 *  source-compatible. */
final case class FrontendModule(
  components:     List[ComponentDef],
  entryPoint:     String,
  initialRoute:   String,
  extraCss:       String             = "",
  targetPlatform: Platform           = Platform.Web,
  appManifest:    Option[AppManifest] = None,
  models:         List[ModelDef]     = Nil
)

/** Lowered component description. */
final case class ComponentDef(
  name:  String,
  props: List[PropDef],
  body:  Any => View[?]
)

final case class PropDef(name: String, paramType: String, default: Option[Any] = None)
