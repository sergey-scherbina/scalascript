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

  test("Toggle reads its checkbox + label from the runtime store") {
    val on  = new ReactiveSignal[Boolean]("t1", true)
    val rs  = emitMain(View.Toggle(on, "Enabled"))
    assert(rs.contains("""toggle_text(signals, "t1", "Enabled")"""))
    assert(rs.contains("""m.insert("t1".to_string(), Value::B(true));"""))
  }

  test("SignalText reads from the runtime signal store (reactive)") {
    val sig = new ReactiveSignal[String]("s1", "live-value")
    val rs  = emitMain(View.SignalText(sig))
    assert(rs.contains("""Paragraph::new(sig(signals, "s1"))"""))
    // store seeded with the initial value
    assert(rs.contains("""m.insert("s1".to_string(), Value::S("live-value".to_string()));"""))
    // and a generated reactivity self-test exists (text signal present)
    assert(rs.contains("fn reactive_rerender()"))
  }

  test("ShowSignal lowers to a runtime if on the signal store") {
    val cond = new ReactiveSignal[Boolean]("c1", true)
    val rs   = emitMain(View.ShowSignal(cond, text("yes"), text("no")))
    assert(rs.contains("""if sig_truthy(signals, "c1") {"""))
    assert(rs.contains("""Paragraph::new("yes")"""))
    assert(rs.contains("""Paragraph::new("no")"""))
  }

  test("emits a crossterm interactive loop + signal store + render(signals)") {
    val rs = emitMain(text("x"))
    assert(rs.contains("fn run_interactive()"))
    assert(rs.contains("event::poll(Duration::from_millis(100))"))
    assert(rs.contains("fn initial_signals()"))
    assert(rs.contains("fn render_root(frame: &mut Frame, area: Rect, signals: &HashMap<String, Value>)"))
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
