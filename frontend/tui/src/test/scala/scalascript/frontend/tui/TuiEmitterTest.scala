package scalascript.frontend.tui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** Slice 1 — fast (no-cargo) string-match checks on the `View → ratatui`
 *  lowering the emitter produces. The end-to-end render is the
 *  assume(cargo)-gated [[TuiCargoSmokeTest]]. */
final class TuiEmitterTest extends AnyFunSuite:

  private def emitMain(root: View[?]): String =
    val mod = FrontendModule(
      components     = List(ComponentDef("App", Nil, _ => root)),
      entryPoint     = "App",
      initialRoute   = "/",
      targetPlatform = Platform.Terminal
    )
    new TuiFrameworkBackend().emitNative(mod, Platform.Terminal).get.sources("src/main.rs")

  private def text(s: String): View[?] = View.Text(() => s)

  test("Column lowers to a vertical Layout split with a Paragraph per child") {
    val rs = emitMain(View.Column(Seq(text("Title"), text("hello"))))
    assert(rs.contains("Layout::vertical("))
    assert(rs.contains("Constraint::Length(1)"))
    assert(rs.contains("""Paragraph::new("Title")"""))
    assert(rs.contains("""Paragraph::new("hello")"""))
  }

  test("Row lowers to a horizontal Layout split (equal ratios)") {
    val rs = emitMain(View.Row(Seq(text("left"), text("right"))))
    assert(rs.contains("Layout::horizontal("))
    assert(rs.contains("Constraint::Ratio(1, 2)"))
    assert(rs.contains("""Paragraph::new("left")"""))
  }

  test("single-child container needs no split — child renders into `area`") {
    val rs = emitMain(View.Column(Seq(text("solo"))))
    assert(!rs.contains("Layout::"))
    assert(rs.contains("""frame.render_widget(Paragraph::new("solo"), area);"""))
  }

  test("nested Column inside Column measures cumulative height") {
    // outer [ text(1), inner Column[ text(1), text(1) ](=2) ] → Length(1), Length(2)
    val rs = emitMain(View.Column(Seq(text("a"), View.Column(Seq(text("b"), text("c"))))))
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Length(2)])"))
  }

  test("Divider lowers to a top-border Block (horizontal rule)") {
    val rs = emitMain(View.Column(Seq(text("a"), View.Divider())))
    assert(rs.contains("Block::new().borders(Borders::TOP)"))
  }

  test("Button renders as static bracketed label") {
    val rs = emitMain(View.Button(text("Click"), EventHandler.Simple(() => ())))
    assert(rs.contains("""Paragraph::new("[Click]")"""))
  }

  test("Toggle renders its checkbox + label from the signal") {
    val on  = new ReactiveSignal[Boolean]("t1", true)
    val rs  = emitMain(View.Toggle(on, "Enabled"))
    assert(rs.contains("""Paragraph::new("[x] Enabled")"""))
  }

  test("SignalText reads the signal value at emit time (static slice)") {
    val sig = new ReactiveSignal[String]("s1", "live-value")
    val rs  = emitMain(View.SignalText(sig))
    assert(rs.contains("""Paragraph::new("live-value")"""))
  }

  test("string content is escaped into a Rust literal") {
    val rs = emitMain(text("say \"hi\"\tend"))
    assert(rs.contains("""Paragraph::new("say \"hi\"\tend")"""))
  }

  test("Spacer reserves rows but renders nothing") {
    val rs = emitMain(View.Column(Seq(text("a"), View.Spacer(Some(2)), text("b"))))
    // three children → vertical split with the spacer measured at 2 rows
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Length(2), Constraint::Length(1)])"))
  }
