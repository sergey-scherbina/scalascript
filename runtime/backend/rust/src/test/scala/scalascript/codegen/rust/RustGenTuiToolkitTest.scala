package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** rust-tui-toolkit S1 — `uiTarget=tui` routes the std/ui `View` through the Rust
 *  codegen backend (the rust-web-toolkit path, where `computedSignal` is a real
 *  re-runnable Rust closure) and renders it to **ratatui** instead of HTML/SSR.
 *  This proves the transpile→ratatui pipeline end to end: a program using
 *  `signal`/`computedSignal`/`signalText`/`serve` emits a `tui.rs` + ratatui
 *  deps, and (cargo-gated) the emitted crate renders the computed value in the
 *  terminal. The live event loop / recompute lands in slice 2. */
class RustGenTuiToolkitTest extends AnyFunSuite:

  private val tuiOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map("binName" -> "tuismoke", "uiTarget" -> "tui")
  )

  private def assets(src: String, opts: BackendOptions): Map[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), opts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case a: Segment.Asset => a.name -> new String(a.bytes, "UTF-8") }.toMap
      case other => fail(s"expected Segmented, got $other")

  private val uiProg =
    """```scalascript
      |@main def run(): Unit =
      |  val loc = signal("locale", "TUI_OK")
      |  val txt = computedSignal(() => loc())
      |  val page = element("div", Map(), Map(), List(
      |    element("h1", Map(), Map(), List(textNode("Title"))),
      |    textNode("base"),
      |    signalText(txt)
      |  ))
      |  serve(page, 0)
      |```
      |""".stripMargin

  test("uiTarget=tui emits tui.rs + ratatui dep + a serve→ratatui shim (no hyper)"):
    val a = assets(uiProg, tuiOpts)
    assert(a.contains("src/runtime/tui.rs"), s"tui.rs must be emitted; got ${a.keySet}")
    assert(a("src/runtime/tui.rs").contains("pub fn _tui_run"))
    assert(a("src/runtime/mod.rs").contains("pub mod tui;"))
    // serve(view, port) → http::_ui_serve, which on the tui target is the ratatui shim
    assert(a("src/runtime/http.rs").contains("crate::runtime::tui::_tui_run"))
    assert(!a("src/runtime/http.rs").contains("hyper"), "tui http.rs must not be the hyper SSR server")
    val cargo = a("Cargo.toml")
    assert(cargo.contains("ratatui"), "tui target must depend on ratatui")
    assert(!cargo.contains("hyper") && !cargo.contains("tokio"), "tui target must not pull the HTTP server deps")

  test("the default (web) target is unchanged — HTML/SSR, no tui.rs"):
    val webOpts = tuiOpts.copy(extra = Map("binName" -> "websmoke"))
    val a = assets(uiProg, webOpts)
    assert(!a.contains("src/runtime/tui.rs"))
    assert(a("Cargo.toml").contains("hyper"), "web target keeps the hyper SSR server")

  // ── cargo end-to-end (gated) ─────────────────────────────────────────────

  private def cargoAvailable: Boolean =
    try os.proc("cargo", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  /** Build `prog`'s tui crate and `cargo run` it headlessly (SSC_TUI_SNAPSHOT —
   *  bypasses the interactive crossterm loop, which needs a TTY). Returns stdout. */
  private def snapshotCrate(prog: String): String =
    val a = assets(prog, tuiOpts)
    val crateDir = os.temp.dir(prefix = "ssc-rust-tui-")
    try
      a.foreach { case (rel, content) =>
        val out = crateDir / os.RelPath(rel)
        os.makeDir.all(out / os.up)
        os.write.over(out, content)
      }
      val res = os.proc("cargo", "run", "--quiet").call(
        cwd = crateDir, check = false, env = Map("SSC_TUI_SNAPSHOT" -> "1"))
      assert(res.exitCode == 0, s"cargo run failed:\n${res.err.text()}")
      res.out.text()
    finally
      os.remove.all(crateDir)

  test("the emitted tui crate compiles + renders the computed value in the terminal"):
    assume(cargoAvailable, "cargo not on PATH — skipping rust-tui cargo smoke")
    val out = snapshotCrate(uiProg)
    assert(out.contains("Title"),  s"heading missing:\n$out")
    assert(out.contains("base"),   s"text missing:\n$out")
    assert(out.contains("TUI_OK"), s"computed signal value missing — computedSignal did not render:\n$out")

  test("S2 — a focused action recomputes a computedSignal LIVE (frame changes on activate)"):
    assume(cargoAvailable, "cargo not on PATH — skipping rust-tui cargo smoke")
    // A computedSignal echoing a signal, and a button that SETS the signal.
    // SSC_TUI_SNAPSHOT renders frame 1, applies the first action + ssc_recompute_all(),
    // and renders frame 2 — proving the derived value updates on a keypress.
    val prog =
      """```scalascript
        |@main def run(): Unit =
        |  val name = signal("name", "BEFORE")
        |  val shown = computedSignal(() => name())
        |  val page = element("div", Map(), Map(), List(
        |    signalText(shown),
        |    element("button", Map(), Map("click" -> setSignal(name, "AFTER")), List(textNode("go")))
        |  ))
        |  serve(page, 0)
        |```
        |""".stripMargin
    val out = snapshotCrate(prog)
    val parts = out.split("---FRAME2---")
    assert(parts.length == 2, s"expected two frames (initial + after-activate), got:\n$out")
    assert(parts(0).contains("BEFORE"), s"initial frame should show the computed value BEFORE:\n${parts(0)}")
    assert(parts(1).contains("AFTER"),  s"after activating the button, the computedSignal must recompute to AFTER (LIVE):\n${parts(1)}")
    assert(!parts(1).contains("BEFORE"), s"the old value should be gone after recompute:\n${parts(1)}")
