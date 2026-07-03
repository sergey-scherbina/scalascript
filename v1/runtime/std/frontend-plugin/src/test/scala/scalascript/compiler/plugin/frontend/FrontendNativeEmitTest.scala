package scalascript.compiler.plugin.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import java.nio.file.Files

/** `emit(view, dir)` must dispatch the active frontend backend to the right
 *  artifact form: a web SPA (`emit` → html/js) or a native app bundle
 *  (`emitNative` → a source tree, e.g. the ratatui `Cargo.toml` +
 *  `src/main.rs` for `tui`). Before this, `emit`/`serve` always called the
 *  web `emit`, so a native-only backend (`tui`) threw
 *  `UnsupportedOperationException`. */
final class FrontendNativeEmitTest extends AnyFunSuite:

  private def module: FrontendModule =
    FrontendModule(List(ComponentDef("App", Nil, _ => View.Text(() => "hi"))), "App", "/")

  private final class DummyTuiBackend extends FrontendFrameworkSpi:
    def name: String = "dummy-tui"
    def capabilities: Set[Capability] = Set.empty
    def jsDeps: List[JsDep] = Nil
    override def supportedPlatforms: Set[Platform] = Set(Platform.Terminal)
    def emit(m: FrontendModule): EmittedSpa =
      throw new UnsupportedOperationException("native-only")
    override def emitNative(m: FrontendModule, platform: Platform): Option[EmittedArtifact.NativeApp] =
      Some(EmittedArtifact.NativeApp(
        sources     = Map("Cargo.toml" -> "[package]\nname = \"x\"\n", "src/main.rs" -> "fn main() {}\n"),
        resources   = Map.empty,
        buildScript = "cargo run",
        manifest    = AppManifest("com.x", "X", "1.0.0"),
        format      = AppFormat.RatatuiApp,
        target      = Platform.Terminal,
      ))

  private final class DummyWebBackend extends FrontendFrameworkSpi:
    def name: String = "dummy-web"
    def capabilities: Set[Capability] = Set.empty
    def jsDeps: List[JsDep] = Nil
    def emit(m: FrontendModule): EmittedSpa = EmittedSpa(js = "//js", html = "<html></html>", css = "")

  test("native backend → emitNative writes the source tree (Cargo.toml + src/main.rs)") {
    val dir = Files.createTempDirectory("ssc-native-emit")
    val log = FrontendIntrinsics.emitFrontendArtifact(new DummyTuiBackend, module, dir)
    assert(Files.exists(dir.resolve("Cargo.toml")))
    assert(Files.exists(dir.resolve("src/main.rs")))          // nested path created
    assert(Files.readString(dir.resolve("src/main.rs")).contains("fn main"))
    assert(log.contains("dummy-tui"))
    assert(log.contains("cargo run"))
  }

  test("web backend → emit writes index.html + app.js") {
    val dir = Files.createTempDirectory("ssc-web-emit")
    val log = FrontendIntrinsics.emitFrontendArtifact(new DummyWebBackend, module, dir)
    assert(Files.exists(dir.resolve("index.html")))
    assert(Files.exists(dir.resolve("app.js")))
    assert(log.contains("index.html"))
  }
