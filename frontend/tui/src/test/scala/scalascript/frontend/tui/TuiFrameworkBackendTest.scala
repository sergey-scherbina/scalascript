package scalascript.frontend.tui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** Slice 0 (scaffold) gate: the backend registers under `name="tui"`, is
 *  selectable via `FrontendFrameworks`, refuses the web `emit` path, and
 *  `emitNative` produces a ratatui crate (`Cargo.toml` + `src/main.rs`)
 *  with the expected markers. The actual `cargo build` of the emitted
 *  crate is the `assume(cargo)`-gated [[TuiCargoSmokeTest]]. */
final class TuiFrameworkBackendTest extends AnyFunSuite:

  private def sampleModule(): FrontendModule =
    FrontendModule(
      components     = List(ComponentDef("App", Nil, _ => View.Text(() => "hello"))),
      entryPoint     = "App",
      initialRoute   = "/",
      targetPlatform = Platform.Terminal
    )

  test("name is 'tui'") {
    assert(new TuiFrameworkBackend().name == "tui")
  }

  test("selectable via FrontendFrameworks.setBackend(\"tui\")") {
    FrontendFrameworks.register(new TuiFrameworkBackend)
    FrontendFrameworks.setBackend("tui")
    try assert(FrontendFrameworks.current().name == "tui")
    finally FrontendFrameworks.setBackend(null) // reset for any sibling tests
  }

  test("registered via META-INF/services (ServiceLoader-discoverable)") {
    assert(FrontendFrameworks.all().exists(_.name == "tui"))
  }

  test("emit (web SPA) throws — tui is native-only") {
    assertThrows[UnsupportedOperationException](new TuiFrameworkBackend().emit(sampleModule()))
  }

  test("emitNative emits a buildable ratatui crate") {
    val art = new TuiFrameworkBackend().emitNative(sampleModule(), Platform.Terminal)
    assert(art.isDefined)
    val app = art.get
    assert(app.format == AppFormat.RatatuiApp)
    assert(app.target == Platform.Terminal)
    assert(app.buildScript == "cargo run")
    assert(app.sources("Cargo.toml").contains("ratatui"))
    val main = app.sources("src/main.rs")
    assert(main.contains("TestBackend"))
    assert(main.contains("fn main()"))
    assert(main.contains("fn render_root"))
    assert(main.contains("buffer_to_lines"))
  }

  test("emitNative returns None for non-terminal platforms") {
    assert(new TuiFrameworkBackend().emitNative(sampleModule(), Platform.Web).isEmpty)
  }

  test("bad entryPoint fails loudly") {
    val mod = sampleModule().copy(entryPoint = "Missing")
    assertThrows[IllegalArgumentException](
      new TuiFrameworkBackend().emitNative(mod, Platform.Terminal).get.sources("src/main.rs")
    )
  }
