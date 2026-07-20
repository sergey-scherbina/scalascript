package scalascript.frontend.tui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** Slice 1 — fast (no-cargo) string-match checks on the `View → ratatui`
 *  lowering the emitter produces. The end-to-end render is the
 *  assume(cargo)-gated [[TuiCargoSmokeTest]]. */
final class TuiEmitterTest extends AnyFunSuite:

  private def emitCrate(root: View[?]): (String, String) =
    val mod = FrontendModule(
      components     = List(ComponentDef("App", Nil, _ => root)),
      entryPoint     = "App",
      initialRoute   = "/",
      targetPlatform = Platform.Terminal
    )
    val app = new TuiFrameworkBackend().emitNative(mod, Platform.Terminal).get
    (app.sources("Cargo.toml"), app.sources("src/main.rs"))

  private def emitMain(root: View[?]): String = emitCrate(root)._2

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

  test("Button renders a bracketed label with a focus marker") {
    val rs = emitMain(View.Button(text("Click"), EventHandler.Simple(() => ())))
    assert(rs.contains("""format!("{}[{}]", focus_mark(focus, 0), "Click")"""))
  }

  test("focusables form a focus ring with handle_key + generated event tests") {
    val count = new ReactiveSignal[Int]("count", 0)
    val name  = new ReactiveSignal[String]("name", "")
    val rs = emitMain(View.Column(Seq(
      View.SignalText(count),
      View.Button(text("inc"), EventHandler.IncrementSignal(count, 1)),
      View.TextInput(name, "type here")
    )))
    assert(rs.contains("const FOCUS_COUNT: usize = 2;"))               // button + text input
    assert(rs.contains("fn handle_key(code: KeyCode"))
    assert(rs.contains("fn activate(focus: usize"))
    assert(rs.contains("matches!(focus, 1)"))                          // text input at idx 1
    // button (idx 0) increments the "count" signal
    assert(rs.contains("""0 => { let cur = match signals.get("count")"""))
    // text input (idx 1) appends typed chars to "name"
    assert(rs.contains("""1 => { let mut s = sig(signals, "name"); s.push(c);"""))
    // generated self-tests for events + typing + focus
    assert(rs.contains("fn event_handlers_run()"))
    assert(rs.contains("fn text_input_typing()"))
    assert(rs.contains("fn tab_moves_focus()"))
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
    assert(rs.contains("fn render_root(frame: &mut Frame, area: Rect, signals: &HashMap<String, Value>, focus: usize)"))
  }

  test("string content is escaped into a Rust literal") {
    val rs = emitMain(text("say \"hi\"\tend"))
    assert(rs.contains("""Paragraph::new("say \"hi\"\tend")"""))
  }

  test("DataTable with static rows lowers to a ratatui Table") {
    val dt = View.DataTable(
      TableDataSource.StaticRows(List(
        Map("room" -> "demo", "unread" -> "2"),
        Map("room" -> "rozum", "unread" -> "5")
      )),
      List(FieldColumnDef("Room", "room"), FieldColumnDef("Unread", "unread")),
      rowKeyPath = "room"
    )
    val rs = emitMain(dt)
    assert(rs.contains("Table::new("))
    assert(rs.contains("""Row::new(vec!["Room", "Unread"])"""))   // header
    assert(rs.contains("""Row::new(vec!["demo", "2"])"""))        // a data row
    assert(rs.contains("""Row::new(vec!["rozum", "5"])"""))
  }

  test("TabBar lowers to a header row + a reactive content match; tabs set the current signal") {
    val tab = new ReactiveSignal[Int]("tab", 0)
    val rs = emitMain(View.TabBar(
      Seq(Tab("Rooms", None, text("rooms panel")), Tab("Models", None, text("models panel"))),
      tab
    ))
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Min(0)])"))
    assert(rs.contains("""match sig_int(signals, "tab")"""))
    assert(rs.contains("""Paragraph::new("rooms panel")"""))
    assert(rs.contains("""Paragraph::new("models panel")"""))
    // each tab header is a focusable whose activation sets the current tab index
    assert(rs.contains("""signals.insert("tab".to_string(), Value::I(0));"""))
    assert(rs.contains("""signals.insert("tab".to_string(), Value::I(1));"""))
  }

  test("DataTable with a Remote source lowers to a runtime fetch_rows table + serde_json dep") {
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val dt = View.DataTable(
      TableDataSource.Remote(feed, "data"),
      List(FieldColumnDef("Room", "room"), FieldColumnDef("Unread", "unread")),
      rowKeyPath = "room"
    )
    val (cargo, rs) = emitCrate(dt)
    assert(cargo.contains("serde_json"))                       // JSON parse dep
    assert(cargo.contains("ureq"))                             // a remote table is also a fetch
    assert(rs.contains("fn fetch_rows("))
    assert(rs.contains("""fetch_rows(&__json, "data", "room", &["room", "unread"])"""))
    assert(rs.contains("""Row::new(vec!["Room", "Unread"])"""))   // header from column titles
    assert(rs.contains("""sig(signals, "rooms")"""))           // reads the bootstrap-fetched body
  }

  test("DataTable rejects duplicate static row identity before rendering") {
    val table = View.DataTable(
      TableDataSource.StaticRows(List(Map("meta" -> Map("key" -> 1)), Map("meta" -> Map("key" -> 1)))),
      List(FieldColumnDef("Key", "meta.key")),
      rowKeyPath = "meta.key")
    val error = intercept[IllegalArgumentException](emitMain(table))
    assert(error.getMessage.contains("duplicate row key int:1"))
  }

  test("NavigationStack lowers to a reactive route match") {
    val route = new ReactiveSignal[String]("route", "home")
    val rs = emitMain(View.NavigationStack(
      Map("home" -> (() => text("home view")), "about" -> (() => text("about view"))),
      route
    ))
    assert(rs.contains("""match sig(signals, "route").as_str()"""))
    assert(rs.contains(""""home" => {"""))
    assert(rs.contains("""Paragraph::new("home view")"""))
  }

  test("fetch-bound SignalText emits bootstrap plus tick-driven refresh and adds the ureq dep") {
    val feed = new FetchUrlSignal("feed", "http://localhost:9/rooms", "tick")
    val (cargo, rs) = emitCrate(View.SignalText(feed))
    assert(cargo.contains("ureq = \"2\""))
    assert(rs.contains("fn fetch_text(url: &str) -> Option<String>"))
    assert(rs.contains("""load_fetch(signals, "feed", "http://localhost:9/rooms")"""))
    assert(rs.contains("fn initial_fetch_ticks(signals: &HashMap<String, Value>)"))
    assert(rs.contains("""observed.insert("feed".to_string(), sig_int(signals, "tick"));"""))
    assert(rs.contains("fn refresh_fetches(signals: &mut HashMap<String, Value>"))
    assert(rs.contains("""let current = sig_int(signals, "tick");"""))
    assert(rs.contains("""observed.get("feed").copied() != Some(current)"""))
    assert(rs.contains("refresh_fetches(&mut signals, &mut observed_fetch_ticks);"))
    assert(rs.contains("bootstrap(&mut signals);"))
  }

  test("non-fetch app has an empty bootstrap and no ureq dependency") {
    val (cargo, rs) = emitCrate(text("hi"))
    assert(!cargo.contains("ureq"))
    assert(rs.contains("fn bootstrap(_signals: &mut HashMap<String, Value>) {}"))
    assert(rs.contains("fn refresh_fetches(_signals: &mut HashMap<String, Value>"))
  }

  test("focusable widget gets a REVERSED focus highlight") {
    val rs = emitMain(View.Button(text("Go"), EventHandler.Simple(() => ())))
    assert(rs.contains("if focus == 0 { Style::default().add_modifier(Modifier::REVERSED) }"))
  }

  test("Style foreground + bold map to ratatui fg + BOLD modifier") {
    val s  = Style(text = TextStyle(foreground = Some(Color.Named("red")), fontWeight = Some(FontWeight.Bold)))
    val rs = emitMain(View.Text(() => "warn", s))
    assert(rs.contains(".style(Style::default().fg(Color::Red).add_modifier(Modifier::BOLD))"))
  }

  test("Styled wrapper pushes a hex color down to the leaf") {
    val s  = Style(text = TextStyle(foreground = Some(Color.Hex("#00ff00"))))
    val rs = emitMain(View.Styled(text("x"), s))
    assert(rs.contains("Color::Rgb(0, 255, 0)"))
  }

  test("unstyled text emits no .style clause") {
    val rs = emitMain(text("plain"))
    assert(rs.contains("""Paragraph::new("plain"), area"""))
  }

  test("Spacer reserves rows but renders nothing") {
    val rs = emitMain(View.Column(Seq(text("a"), View.Spacer(Some(2)), text("b"))))
    // three children → vertical split with the spacer measured at 2 rows
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Length(2), Constraint::Length(1)])"))
  }
