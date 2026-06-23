package scalascript.frontend.tui

import scalascript.frontend.*

/** Terminal-UI frontend backend — lowers the framework-agnostic `View` IR
 *  onto **ratatui + crossterm** Rust, so the same `std/ui` (Tk) `.ssc` app
 *  that compiles to web (React/SSR) and JVM desktop (Swing/JavaFX) also
 *  compiles to a native terminal binary.
 *
 *  This is the scalascript-side half of the rozum Unified Control Center
 *  (see `specs/frontend-tui-ratatui.md`). It follows the **native** SPI
 *  pattern of `swing`/`javafx`: `emit` (web SPA) throws; `emitNative`
 *  returns a self-contained Rust crate (`Cargo.toml` + `src/main.rs`) built
 *  with `cargo run` — it does NOT go through the Rust codegen backend
 *  (`RustCodeWalk`).
 *
 *  Slice 0 (scaffold): `emitNative` produces a minimal buildable ratatui
 *  crate that renders one empty frame via ratatui's headless `TestBackend`
 *  and exits. The `View → ratatui` lowering table lands in slices 1+. */
final class TuiFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "tui"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState
  )

  override def jsDeps: List[JsDep] = Nil

  override def supportedPlatforms: Set[Platform] = Set(Platform.Terminal)

  override def emit(module: FrontendModule): EmittedSpa =
    throw UnsupportedOperationException(
      "tui is a terminal frontend; use emitNative(..., Platform.Terminal)"
    )

  override def emitNative(
    module:   FrontendModule,
    platform: Platform
  ): Option[EmittedArtifact.NativeApp] =
    platform match
      case Platform.Terminal | Platform.All =>
        val manifest = module.appManifest.getOrElse(
          AppManifest("com.example.app", "ScalaScript TUI", "1.0.0")
        )
        Some(EmittedArtifact.NativeApp(
          sources     = Map(
            "Cargo.toml"   -> TuiEmitter.cargoToml(manifest),
            "src/main.rs"  -> TuiEmitter.mainRs(module, manifest)
          ),
          resources   = Map.empty,
          buildScript = "cargo run",
          manifest    = manifest,
          format      = AppFormat.RatatuiApp,
          target      = platform
        ))
      case _ => None
