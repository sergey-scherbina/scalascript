package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit smoke for the `emit-spa --frontend <name>` flag's CLI-side
 *  helper.  v1.18 / Phase A7.
 *
 *  Confirms:
 *
 *  - `validFrontendNames` covers exactly the four bundled backends.
 *  - `applyFrontendBackend(name)` flips the `FrontendFrameworks`
 *    registry choice for downstream codegen.
 *  - `applyFrontendBackend("nope")` throws (defense — the CLI's arg
 *    parser validates upstream against `validFrontendNames`, but
 *    `FrontendFrameworks.setBackend` raises if no impl with that
 *    name is on the classpath). */
class FrontendBackendSelectionTest extends AnyFunSuite:

  test("validFrontendNames lists the bundled frontend backends (incl. tui)") {
    assert(validFrontendNames == Set("custom", "react", "solid", "vue", "electron", "swing", "javafx", "swiftui", "tui"))
  }

  test("the `tui` command is registered (live ratatui runner, alias run-tui)") {
    assert(CommandRegistry.lookup("tui").exists(_.name == "tui"))
    assert(CommandRegistry.lookup("run-tui").exists(_.name == "tui"))
  }

  // S5 — the CLI live-path emit step (shared by `tui` and `run --frontend tui`):
  // compileViaBackend('rust', uiTarget=tui) must yield the ratatui crate (tui.rs +
  // ratatui dep, no hyper SSR server), not the web SSR crate.
  test("compileViaBackend('rust', uiTarget=tui) emits the live ratatui crate") {
    val dir = os.temp.dir(prefix = "ssc-tui-cli-")
    try
      val f = dir / "demo.ssc"
      os.write(f,
        """```scalascript
          |@main def run(): Unit =
          |  val loc  = signal("locale", "TUI_OK")
          |  val page = element("div", Map(), Map(), List(signalText(computedSignal(() => loc()))))
          |  serve(page, 0)
          |```
          |""".stripMargin)
      compileViaBackend("rust", f, Map("binName" -> "demo", "uiTarget" -> "tui")) match
        case scalascript.backend.spi.CompileResult.Segmented(segs) =>
          val assets = segs.collect { case a: scalascript.backend.spi.Segment.Asset => a }
          val names  = assets.map(_.name).toSet
          assert(names.contains("src/runtime/tui.rs"), s"no tui.rs emitted; assets: $names")
          val cargo = assets.find(_.name == "Cargo.toml").map(a => new String(a.bytes, "UTF-8")).getOrElse("")
          assert(cargo.contains("ratatui"), s"Cargo.toml missing ratatui:\n$cargo")
          assert(!cargo.contains("hyper"),  s"tui crate must not pull the hyper SSR server:\n$cargo")
        case other =>
          fail(s"expected Segmented crate, got ${other.getClass.getSimpleName}")
    finally os.remove.all(dir)
  }

  test("applyFrontendBackend('react') selects react") {
    try
      applyFrontendBackend("react")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("react"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("applyFrontendBackend('custom') selects custom") {
    try
      applyFrontendBackend("custom")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("custom"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("applyFrontendBackend throws on unknown name") {
    try
      val ex = intercept[IllegalStateException] {
        applyFrontendBackend("svelte")
      }
      assert(ex.getMessage.contains("svelte"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("all four bundled backends round-trip through applyFrontendBackend") {
    try
      for name <- List("custom", "react", "solid", "vue") do
        applyFrontendBackend(name)
        assert(scalascript.frontend.FrontendFrameworks.selectedName == Some(name))
        assert(scalascript.frontend.FrontendFrameworks.current().name == name)
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }
