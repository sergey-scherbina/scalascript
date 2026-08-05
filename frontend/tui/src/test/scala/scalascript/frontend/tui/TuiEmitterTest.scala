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

  test("a remote table tolerates a body it cannot read; a static one still must not") {
    // Found running the generated client as a shipped binary: `expect` on the parse killed the
    // process on any non-JSON body — a 401 while a credential is missing, a 500, an offline
    // daemon. Every gate until then pointed a remote table at a healthy fixture, so the failure
    // path had never run.
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val remote = View.DataTable(TableDataSource.Remote(feed, "entries"),
      List(FieldColumnDef("Room", "name")), rowKeyPath = "name")
    val rs = emitMain(remote)
    assert(rs.contains("""table_rows("rooms", fetch_rows("""))
    assert(rs.contains("fn table_rows("))
    assert(!rs.contains("""expect("invalid DataTable row identity")"""),
      "a remote table must not panic on a body it cannot read")

    // The strict path stays where it belongs: a StaticRows identity is known at emit time, so a
    // bad one is a source defect and SHOULD stop the build's own tests rather than render empty.
    val static = View.DataTable(
      TableDataSource.StaticRows(List(Map("a" -> "1"))),
      List(FieldColumnDef("A", "a")), rowKeyPath = "a")
    val rs2 = emitMain(static)
    assert(!rs2.contains("fn table_rows("), "a static table needs no last-good cache")
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

  test("a fetch with a headers signal sets them on the GET and pulls in serde_json") {
    // Reported by rozum (INBOX tui-fetch-headers): FetchUrlSignal carries headersId, the web target
    // honours it, the TUI target dropped it — so an authenticated source emitted a bare GET.
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick", Some("auth"))
    val (cargo, rs) = emitCrate(View.SignalText(feed))
    assert(cargo.contains("serde_json"))                         // the headers path parses JSON too
    assert(rs.contains("fn fetch_headers("))
    // Since tui-credential-resolution the header list is a MUTABLE local, because a declared
    // credential is appended to it after the signal is read. The binding this test guards —
    // headers resolved at fetch time and handed to load_fetch — is unchanged.
    assert(rs.contains("""let mut __h = fetch_headers(signals, "auth");"""))
    assert(rs.contains("""load_fetch(signals, "rooms", "http://x/rooms", &__h);"""))
    assert(rs.contains("req = req.set(name, value);"))           // applied to the request
  }

  test("a signal-URL fetch resolves the URL at fetch time and re-fetches when it changes") {
    // Reported by rozum (INBOX tui-fetch-url-signal): a picker retargets the GET on the web and the
    // terminal binary kept reading whichever endpoint was resolved at emit time.
    val feed = new FetchUrlSignal("rows", "", "tick", None, Some("urlSig"))
    val (_, rs) = emitCrate(View.SignalText(feed))
    assert(rs.contains("""load_fetch(signals, "rows", &sig(signals, "urlSig"), &[]);"""))
    assert(!rs.contains("""load_fetch(signals, "rows", "", &[]);"""))   // never the emit-time literal
    // The observed state is the PAIR. Remembering only the tick would mean a retarget with an
    // unchanged tick never re-fetches — the reported bug, one layer down — so this assertion is
    // the actual regression guard, not the load_fetch line above.
    assert(rs.contains("""format!("{} {}", sig_int(signals, "tick"), sig(signals, "urlSig"))"""))
    assert(rs.contains("fn refresh_fetches(signals: &mut HashMap<String, Value>, observed: &mut HashMap<String, String>)"))
    // An empty URL is a picker with nothing selected: no request, last good value kept.
    assert(rs.contains("if url.is_empty() { return; }"))
  }

  test("a literal-URL fetch is unchanged by the signal-URL support") {
    // The negative half: sources that never asked for a signal URL must emit exactly what they did
    // before — the literal inline, and no signal read on the URL path.
    val feed = new FetchUrlSignal("rows", "http://x/rows", "tick")
    val (_, rs) = emitCrate(View.SignalText(feed))
    assert(rs.contains("""load_fetch(signals, "rows", "http://x/rows", &[]);"""))
    assert(rs.contains("""format!("{}", sig_int(signals, "tick"))"""))  // tick alone is the state
    assert(!rs.contains("""&sig(signals, "urlSig")"""))
  }

  test("a signal-URL fetch composes with headers — both resolved at the same moment") {
    val feed = new FetchUrlSignal("rows", "", "tick", Some("auth"), Some("urlSig"))
    val (_, rs) = emitCrate(View.SignalText(feed))
    assert(rs.contains("""let mut __h = fetch_headers(signals, "auth");"""))
    assert(rs.contains("""load_fetch(signals, "rows", &sig(signals, "urlSig"), &__h);"""))
  }

  test("a fetchAction button posts the body and bumps the tick — only on success") {
    // Reported by rozum (INBOX tui-fetch-post): EventHandler.FetchAction is in the model and
    // FetchIntrinsics builds it, but activationOf dropped it — so a composer rendered, took
    // keystrokes, and silently never sent.
    val body = new ReactiveSignal[String]("draft", "")
    val tick = new ReactiveSignal[Int]("rowsTick", 0)
    val send = View.Button(View.Text(() => "send"),
      EventHandler.FetchAction("POST", "http://x/messages", body, tick, clearBody = true))
    val (cargo, rs) = emitCrate(send)
    assert(cargo.contains("ureq"))                                    // derived from the emission
    assert(rs.contains("fn send_action("))
    assert(rs.contains("""send_action(signals, "POST", "http://x/messages", "draft", "rowsTick", true, &__h);"""))
    assert(rs.contains("""req = req.set("Content-Type", "application/json");"""))
    // The asymmetry is the point: both mutations sit INSIDE the success branch. Bumping the tick
    // before the response refreshes a list that was never written, and clearing the body on a
    // failed send eats the user's message.
    val success = rs.substring(rs.indexOf("if req.send_string(&body).is_ok() {"))
    assert(success.take(400).contains("""signals.insert(tick_id.to_string(), Value::I(cur + 1));"""))
    assert(success.take(400).contains("""if clear_body {"""))
  }

  test("an app with only local handlers emits no send_action and no ureq") {
    // The negative half: the write helper must not follow every button around.
    val flag = new ReactiveSignal[Boolean]("flag", false)
    val (cargo, rs) = emitCrate(View.Button(View.Text(() => "toggle"), EventHandler.ToggleSignal(flag)))
    assert(!rs.contains("fn send_action("))
    assert(!cargo.contains("ureq"))
  }

  test("a POST that is the only user of headers still gets fetch_headers emitted") {
    // fetch_headers normally rides along with a header-bound GET. When a POST is its only user the
    // crate would reference a function that was never emitted — a break a string assertion on the
    // call site alone would not catch.
    val body = new ReactiveSignal[String]("draft", "")
    val tick = new ReactiveSignal[Int]("t", 0)
    val auth = new ReactiveSignal[String]("auth", """{"Authorization":"Bearer t"}""")
    val send = View.Button(View.Text(() => "send"),
      EventHandler.FetchAction("POST", "http://x/m", body, tick, clearBody = false, headers = Some(auth)))
    val (cargo, rs) = emitCrate(send)
    assert(rs.contains("fn fetch_headers("))
    // `mut` since credentials: the write path appends a resolved credential to the same list.
    assert(rs.contains("""let mut __h = fetch_headers(signals, "auth");"""))
    assert(cargo.contains("serde_json"))                              // fetch_headers parses JSON
  }

  test("a table with a RowLink becomes one focusable with a row cursor") {
    // Reported by rozum (INBOX tui-table-selection). RowActionDef.RowLink already means 'choosing
    // this row writes row[fieldPath] into signal'; the terminal emitter discarded actions entirely.
    val picked = new ReactiveSignal[String]("picked", "")
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val dt = View.DataTable(
      TableDataSource.Remote(feed, "rooms"),
      List(FieldColumnDef("Room", "name")),
      actions = List(RowActionDef.RowLink("open", picked, "url")),
      rowKeyPath = "name"
    )
    val (_, rs) = emitCrate(dt)
    // ONE focusable for the whole table — row count is runtime, the focus ring is build-time.
    assert(rs.contains("const FOCUS_COUNT: usize = 1;"))
    assert(rs.contains("fn is_table(focus: usize) -> bool { matches!(focus, 0) }"))
    assert(rs.contains("fn move_row(focus: usize"))
    assert(rs.contains("""row_count(&sig(signals, "rooms"), "rooms")"""))
    // Enter writes the cursor row's field into the bound signal.
    assert(rs.contains("""row_field(&sig(signals, "rooms"), "rooms", __i, "url")"""))
    assert(rs.contains("""signals.insert("picked".to_string(), Value::S(__v));"""))
    // A real ratatui selection, not a marker glued into a cell.
    assert(rs.contains("render_stateful_widget"))
    assert(rs.contains("row_highlight_style"))
  }

  test("a table WITHOUT a RowLink emits no focusable and no cursor machinery") {
    // The negative half. This is what keeps every existing app byte-identical: arrows must go on
    // moving FOCUS when nothing on screen wants them.
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val dt = View.DataTable(
      TableDataSource.Remote(feed, "rooms"),
      List(FieldColumnDef("Room", "name")),
      rowKeyPath = "name"
    )
    val (_, rs) = emitCrate(dt)
    assert(rs.contains("const FOCUS_COUNT: usize = 0;"))
    assert(rs.contains("fn is_table(_focus: usize) -> bool { false }"))
    assert(rs.contains("fn move_row(_focus: usize"))
    assert(!rs.contains("render_stateful_widget"))
    assert(!rs.contains("fn row_field("))
  }

  test("a remote table in a column takes the remaining height, its siblings do not") {
    // Found building a room picker: a remote table measured 1, so the header rendered and no row
    // did — with the fetch working and the rows sitting in the store. Everything looked healthy.
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val table = View.DataTable(TableDataSource.Remote(feed, "entries"),
      List(FieldColumnDef("Room", "name")), rowKeyPath = "name")
    val rs = emitMain(View.Column(Seq(text("title"), table, text("footer"))))
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Min(3), Constraint::Length(1)])"),
      s"unexpected constraints:\n$rs")
  }

  test("a column with no remote table emits exactly the constraints it emitted before") {
    // The negative half, and the one that protects every existing layout: nothing else may start
    // flexing just because tables can.
    val rs = emitMain(View.Column(Seq(text("a"), text("b"))))
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Length(1)])"))
    assert(!rs.contains("Constraint::Min("))
  }

  test("a static-rows table still measures its rows exactly") {
    val dt = View.DataTable(
      TableDataSource.StaticRows(List(Map("a" -> "1"), Map("a" -> "2"))),
      List(FieldColumnDef("A", "a")), rowKeyPath = "a")
    val rs = emitMain(View.Column(Seq(text("t"), dt)))
    // two rows + header = 3, and FIXED — a knowable height must not become flexible.
    assert(rs.contains("Layout::vertical([Constraint::Length(1), Constraint::Length(3)])"), s"\n$rs")
  }

  test("a fetch bound to an interval tick emits a wall clock for it") {
    // Reported by rozum: IntervalTick is in the model, the JS runtime implements it, and this
    // emitter had zero references — so a source that auto-polls on the web emitted a binary that
    // only ever refreshed on a keypress.
    val tick = new IntervalTick("poll", 5000)
    val feed = new FetchUrlSignal("feed", "http://x/f", tick.id, None, None, Some(tick.ms))
    val rs = emitMain(View.SignalText(feed))
    assert(rs.contains("fn bump_interval_ticks(signals: &mut HashMap<String, Value>"))
    assert(rs.contains("""last.entry("poll".to_string())"""))
    assert(rs.contains("5000u128"))
    // Driven before the refresh in the same iteration, or a due tick waits a frame.
    val loop = rs.substring(rs.indexOf("fn run_interactive"))
    assert(loop.indexOf("bump_interval_ticks(&mut signals") < loop.indexOf("refresh_fetches(&mut signals"),
      "the clock must advance before the refresh it feeds")
  }

  test("two fetches sharing one interval tick advance it once") {
    // A shared tick is ONE clock. Bumping per fetch would double the poll rate — invisible in a
    // snapshot, and only ever noticed as a server being hit twice as often as configured.
    val tick = new IntervalTick("poll", 1000)
    val a = new FetchUrlSignal("a", "http://x/a", tick.id, None, None, Some(tick.ms))
    val b = new FetchUrlSignal("b", "http://x/b", tick.id, None, None, Some(tick.ms))
    val rs = emitMain(View.Column(Seq(View.SignalText(a), View.SignalText(b))))
    assert(rs.split("""last\.entry\("poll"""").length - 1 == 1,
      s"the shared tick is bumped more than once:\n$rs")
  }

  test("a plain tick emits no clock at all") {
    // The negative half: a hand-bumped tick must not acquire a timer, and an app with no interval
    // must carry no bookkeeping.
    val feed = new FetchUrlSignal("feed", "http://x/f", "tick")
    val rs = emitMain(View.SignalText(feed))
    assert(rs.contains("fn bump_interval_ticks(_signals:"))
    assert(!rs.contains("last.entry("))
  }

  test("a WRITE carries the credential too — a declared GET and a baked POST still ships the token") {
    val body = new ReactiveSignal[String]("draft", "")
    val tick = new ReactiveSignal[Int]("t", 0)
    val send = View.Button(View.Text(() => "send"),
      EventHandler.FetchAction("POST", "http://x/m", body, tick, clearBody = true, headers = None,
        credential = Some(("env", "ROZUM_MEETING_TOKEN", "Bearer"))))
    val rs = emitMain(send)
    assert(rs.contains("fn resolve_credential("))
    assert(rs.contains("""resolve_credential("env", "ROZUM_MEETING_TOKEN", "Bearer")"""))
    assert(rs.contains("""__h.push(("Authorization".to_string(), __a));"""))
    assert(rs.contains("send_action(signals,"))
    // Same property as the read path: only the NAME travels.
    assert(!rs.contains("Bearer ROZUM"), "a secret-shaped literal reached the emission")
  }

  test("an env credential is resolved on the target, and its NAME is all the emitter sees") {
    // S2 of ui-fetch-credentials. The assertion that matters is the last one: everything else can
    // pass while the secret is still baked into the binary, which is the whole thing this prevents.
    val feed = new FetchUrlSignal("feed", "http://x/f", "tick", None, None, None,
      Some(("env", "ROZUM_MEETING_TOKEN", "Bearer")))
    val rs = emitMain(View.SignalText(feed))
    assert(rs.contains("fn resolve_credential("))
    assert(rs.contains("""std::env::var(source)"""))
    assert(rs.contains("""resolve_credential("env", "ROZUM_MEETING_TOKEN", "Bearer")"""))
    assert(rs.contains("""__h.push(("Authorization".to_string(), __a));"""))
    // The emitter only ever handled a NAME — there is no value in the source to leak.
    assert(!rs.contains("Bearer ROZUM"), "a secret-shaped literal reached the emission")
  }

  test("a credential composes with a headers signal, and the signal keeps winning") {
    val feed = new FetchUrlSignal("feed", "http://x/f", "tick", Some("auth"), None, None,
      Some(("file", "~/.rozum/token", "Basic")))
    val rs = emitMain(View.SignalText(feed))
    // Headers first, credential appended — `fetch_text` sets them in order, so an explicit
    // Authorization already in the signal is not overwritten by the declaration.
    val call = rs.substring(rs.indexOf("let mut __h = fetch_headers"))
    assert(call.take(300).contains("""resolve_credential("file", "~/.rozum/token", "Basic")"""))
    assert(rs.contains("std::fs::read_to_string"))
  }

  test("a fetch with no credential emits no resolver at all") {
    // The negative half: every existing crate must be byte-identical, and nothing may acquire a
    // filesystem or environment read it did not ask for.
    val feed = new FetchUrlSignal("feed", "http://x/f", "tick")
    val rs = emitMain(View.SignalText(feed))
    assert(!rs.contains("fn resolve_credential("))
    assert(!rs.contains("std::fs::read_to_string"))
  }

  test("a fetch WITHOUT headers stays header-free and serde_json-free") {
    // The other half, and the one that keeps the no-header path cheap: emitting fetch_headers
    // unconditionally would reference serde_json in every crate that fetches anything.
    val feed = new FetchUrlSignal("rooms", "http://x/rooms", "tick")
    val (cargo, rs) = emitCrate(View.SignalText(feed))
    assert(!cargo.contains("serde_json"))
    assert(!rs.contains("fn fetch_headers("))
    assert(rs.contains("""load_fetch(signals, "rooms", "http://x/rooms", &[]);"""))
  }

  test("Cargo dependencies are derived from the emitted source, not from a feature list") {
    // BUGS tui-cargo-deps-are-a-hand-maintained-disjunction. The manifest is computed from the
    // generated Rust, so a future emission that reaches for a crate cannot desynchronise from it.
    // All four directions are pinned, because only the NEGATIVES keep the derivation honest — an
    // over-declaring manifest still compiles and would pass every positive assertion.
    val noFetch = emitCrate(View.Text(() => "static"))._1
    assert(!noFetch.contains("ureq"))
    assert(!noFetch.contains("serde_json"))

    val plain = emitCrate(View.SignalText(new FetchUrlSignal("f", "http://x/a", "t")))._1
    assert(plain.contains("ureq"))
    assert(!plain.contains("serde_json"))          // no headers, no JSON parsing, no dependency

    val withHeaders = emitCrate(View.SignalText(new FetchUrlSignal("f", "http://x/a", "t", Some("h"))))._1
    assert(withHeaders.contains("ureq") && withHeaders.contains("serde_json"))

    val remote = emitCrate(View.DataTable(
      TableDataSource.Remote(new FetchUrlSignal("rows", "http://x/rows", "t"), "data"),
      List(FieldColumnDef("Room", "room")),
      rowKeyPath = "room"))._1
    assert(remote.contains("ureq") && remote.contains("serde_json"))
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
    assert(rs.contains("fn fetch_text(url: &str, headers: &[(String, String)]) -> Option<String>"))
    // `&[]` since tui-fetch-headers: load_fetch now carries the resolved header pairs, and a fetch
    // that binds no headers passes an empty slice rather than a different function.
    assert(rs.contains("""load_fetch(signals, "feed", "http://localhost:9/rooms", &[]);"""))
    assert(rs.contains("fn initial_fetch_ticks(signals: &HashMap<String, Value>)"))
    // Since tui-fetch-url-signal the observed state is a STRING, not an i64: a signal-URL fetch has
    // two triggers (tick and URL) and what is remembered has to be the pair. A literal-URL fetch
    // like this one still remembers the tick alone — just formatted — so the refresh contract this
    // test guards is unchanged; only its representation moved.
    assert(rs.contains("""observed.insert("feed".to_string(), format!("{}", sig_int(signals, "tick")));"""))
    assert(rs.contains("fn refresh_fetches(signals: &mut HashMap<String, Value>"))
    assert(rs.contains("""let current = format!("{}", sig_int(signals, "tick"));"""))
    assert(rs.contains("""observed.get("feed") != Some(&current)"""))
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
