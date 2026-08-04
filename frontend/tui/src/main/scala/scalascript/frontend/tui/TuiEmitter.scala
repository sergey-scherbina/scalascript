package scalascript.frontend.tui

import scalascript.frontend.*
import scala.collection.mutable

/** Emits the ratatui Rust crate from a `FrontendModule`.
 *
 *  Progression: slice 1 lowered the static `View` tree to `render_root`;
 *  slice 2 added a runtime signal store + a crossterm redraw loop; **slice
 *  3** makes it interactive — a **focus ring** over the focusable widgets
 *  (`Button`/`TextInput`/`Toggle`) with Tab/arrow traversal, crossterm
 *  `KeyEvent` dispatch, and `EventHandler` execution that **mutates** the
 *  signal store (declarative handlers: `SetSignalLiteral`/`IncrementSignal`/
 *  `ToggleSignal`; `TextInput` editing via typed chars). `Simple`/`WithEvent`
 *  are Scala closures with no Rust equivalent → no-op. Reactive/list nodes
 *  (`ForSignal`, table data) and fetch-bound signals land in slices 4–5;
 *  `Style`/`Theme` mapping remains deferred.
 *
 *  Focus order is document order (a `ShowSignal` contributes both branches'
 *  focusables; runtime focus on a hidden branch is a known slice-3 edge). */
object TuiEmitter:

  /** Initial value + whether the signal renders as text (drives the
   *  generated reactivity self-test, which needs a text-rendering signal). */
  private final case class SigInfo(initExpr: String, isText: Boolean)

  /** Managed GET metadata. `tickId` is part of the binding contract: a
   *  changed tick schedules a new GET before the next terminal frame. */
  /** `headersId` is the signal holding a JSON object of header name -> value, read at FETCH time
   *  (not at emit time) exactly as the web targets read it. `None` when the source bound no headers,
   *  which must stay dependency-free — see `cargoToml`. */
  private final case class FetchInfo(url: String, tickId: String, headersId: Option[String],
                                     urlId: Option[String], tickMs: Option[Int],
                                     credential: Option[(String, String, String)])

  /** A declarative store mutation an `activate` arm performs. */
  private enum Mutation:
    case Set(id: String, valueExpr: String)
    case Incr(id: String, by: Int)
    case Toggle(id: String)
    /** `fetchAction` — a WRITE. The store is mutated only after a 2xx (see `send_action`). */
    case Post(method: String, url: String, bodyId: String, tickId: String,
              clearBody: Boolean, headersId: Option[String],
              credential: Option[(String, String, String)])
    /** `RowLink` — choosing the cursor row writes one of its fields into the bound signal. */
    case PickRow(cursorId: String, fetchId: String, rowsPath: String, fieldPath: String, targetId: String)

  /** Everything a selectable table's movement AND its write need, so neither re-derives it.
   *  `cursorId` is an ordinary Int signal in the store — no new state plumbing, and the generated
   *  self-tests can see it. */
  private final case class TableCursor(cursorId: String, fetchId: String, rowsPath: String,
                                       fieldPath: String, targetId: String)

  /** A focusable widget, in document order; `idx` is its focus-ring index. */
  private final case class Focusable(idx: Int, activation: Option[Mutation], textSignalId: Option[String],
                                     cursor: Option[TableCursor] = None)

  /** The whole emitted crate: `(Cargo.toml, src/main.rs)`. `ureq` is added
   *  only when the app has fetch-bound signals (keeps non-fetch crates lean). */
  def crate(module: FrontendModule, manifest: AppManifest): (String, String) =
    val entry = module.components.find(_.name == module.entryPoint).getOrElse(
      throw new IllegalArgumentException(
        s"FrontendModule.entryPoint='${module.entryPoint}' not found among " +
        s"components [${module.components.map(_.name).mkString(", ")}]."
      )
    )
    val root = NativeElementLowering.lower(entry.body(()))

    val signals = mutable.LinkedHashMap.empty[String, SigInfo]
    collectSignals(root, signals)

    val fetches = mutable.LinkedHashMap.empty[String, FetchInfo]
    collectFetches(root, fetches)

    val remoteTable = hasRemoteTable(root)

    val focusables = mutable.ArrayBuffer.empty[Focusable]
    val body = StringBuilder()
    emit(root, "area", body, Iterator.from(0), focusables, TermStyle.empty)

    // The manifest is DERIVED FROM THE EMITTED SOURCE, so it cannot disagree with it. It used to be
    // a disjunction over the features known to use each crate (`hasRemoteTable`, then also the
    // headers path); every term was a chance to forget one, and forgetting emits a crate that does
    // not compile — invisible to a string-matching emitter test, which asserts the generated Rust
    // contains a call and never looks at the manifest. BUGS.md
    // tui-cargo-deps-are-a-hand-maintained-disjunction.
    val rs = mainRs(manifest, signals, fetches, remoteTable, focusables, body)
    (cargoToml(manifest, rs), rs)

  /** Dependencies are read out of the emitted source: a crate is declared exactly when the
   *  generated Rust names it. Adding an emission that uses one needs no change here. */
  private def cargoToml(manifest: AppManifest, emittedSource: String): String =
    val ureq  = if emittedSource.contains("ureq::")       then "ureq = \"2\"\n"       else ""
    val serde = if emittedSource.contains("serde_json::") then "serde_json = \"1\"\n" else ""
    s"""[package]
       |name = "${crateName(manifest)}"
       |version = "${manifest.version}"
       |edition = "2021"
       |
       |[[bin]]
       |name = "${crateName(manifest)}"
       |path = "src/main.rs"
       |
       |[dependencies]
       |ratatui = "0.29"
       |$ureq$serde""".stripMargin

  private def mainRs(
      manifest:    AppManifest,
      signals:     mutable.LinkedHashMap[String, SigInfo],
      fetches:     mutable.LinkedHashMap[String, FetchInfo],
      remoteTable: Boolean,
      focusables:  mutable.ArrayBuffer[Focusable],
      body:        StringBuilder,
  ): String =
    s"""// Generated by scalascript frontend/tui (ratatui backend) — slice 5 (fetch-binding).
       |// App: ${manifest.displayName}
       |#![allow(unused_imports, unused_variables, dead_code)]
       |use std::collections::HashMap;
       |use std::io;
       |use std::time::Duration;
       |use ratatui::backend::{TestBackend, CrosstermBackend};
       |use ratatui::{Terminal, Frame};
       |use ratatui::layout::{Layout, Constraint, Rect};
       |use ratatui::widgets::{Paragraph, Block, Borders, Table, Row};
       |use ratatui::style::{Style, Modifier, Color};
       |use ratatui::buffer::Buffer;
       |use ratatui::crossterm::event::{self, Event, KeyCode, KeyEventKind};
       |use ratatui::crossterm::terminal::{enable_raw_mode, disable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen};
       |use ratatui::crossterm::execute;
       |
       |${valueEnumAndHelpers()}
       |
       |fn initial_signals() -> HashMap<String, Value> {
       |    let mut m: HashMap<String, Value> = HashMap::new();
       |${emitSignalSeed(signals)}    m
       |}
       |
       |${genFetchHelpers(fetches)}
       |${genWriteHelpers(fetches, focusables.toSeq)}\n       |${genIntervalHelpers(fetches)}
       |${genTableHelpers(remoteTable)}
       |${genRowCursorHelpers(focusables.toSeq)}\n       |${genCredentialHelpers(fetches, focusables.exists(_.activation.exists { case Mutation.Post(_, _, _, _, _, _, c) => c.isDefined; case _ => false }))}
       |
       |${genFocusConsts(focusables.toSeq)}
       |
       |${genActivate(focusables.toSeq)}
       |
       |${genTypeChar(focusables.toSeq)}
       |
       |${genBackspace(focusables.toSeq)}
       |
       |${genHandleKey()}
       |
       |fn render_root(frame: &mut Frame, area: Rect, signals: &HashMap<String, Value>, focus: usize) {
       |${body.toString.stripLineEnd}
       |}
       |
       |fn buffer_to_lines(buf: &Buffer) -> String {
       |    let area = buf.area;
       |    let mut out = String::new();
       |    for y in 0..area.height {
       |        let mut line = String::new();
       |        for x in 0..area.width {
       |            line.push_str(buf[(x, y)].symbol());
       |        }
       |        out.push_str(line.trim_end());
       |        out.push('\\n');
       |    }
       |    out
       |}
       |
       |fn render_to_string(width: u16, height: u16, signals: &HashMap<String, Value>, focus: usize) -> String {
       |    let mut terminal = Terminal::new(TestBackend::new(width, height)).expect("terminal init");
       |    terminal.draw(|f| { let area = f.area(); render_root(f, area, signals, focus); }).expect("draw");
       |    buffer_to_lines(terminal.backend().buffer())
       |}
       |
       |fn run_interactive() -> io::Result<()> {
       |    enable_raw_mode()?;
       |    let mut stdout = io::stdout();
       |    execute!(stdout, EnterAlternateScreen)?;
       |    let mut terminal = Terminal::new(CrosstermBackend::new(stdout))?;
       |    let mut signals = initial_signals();
       |    bootstrap(&mut signals);
       |    let mut observed_fetch_ticks = initial_fetch_ticks(&signals);
       |    let mut last_interval: HashMap<String, std::time::Instant> = HashMap::new();
       |    let mut focus: usize = 0;
       |    let result = (|| -> io::Result<()> {
       |        loop {
       |            // Advance auto-polling ticks BEFORE the refresh, so a due tick is acted on in
       |            // the frame that notices it rather than the next one.
       |            bump_interval_ticks(&mut signals, &mut last_interval);
       |            refresh_fetches(&mut signals, &mut observed_fetch_ticks);
       |            terminal.draw(|f| { let area = f.area(); render_root(f, area, &signals, focus); })?;
       |            if event::poll(Duration::from_millis(100))? {
       |                if let Event::Key(key) = event::read()? {
       |                    if key.kind == KeyEventKind::Press {
       |                        if handle_key(key.code, &mut signals, &mut focus) { break; }
       |                    }
       |                }
       |            }
       |        }
       |        Ok(())
       |    })();
       |    disable_raw_mode()?;
       |    execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
       |    result
       |}
       |
       |fn main() {
       |    if std::env::var("SSC_TUI_SNAPSHOT").is_ok() {
       |        let mut signals = initial_signals();
       |        bootstrap(&mut signals);
       |        print!("{}", render_to_string(80, 24, &signals, 0));
       |    } else {
       |        let _ = run_interactive();
       |    }
       |}
       |${genTests(signals, focusables.toSeq, remoteTable)}""".stripMargin

  // ── Emitted Rust runtime: Value + accessors ────────────────────────────

  private def valueEnumAndHelpers(): String =
    """#[derive(Clone, PartialEq, Debug)]
      |enum Value { S(String), I(i64), B(bool) }
      |impl Value {
      |    fn display(&self) -> String {
      |        match self { Value::S(s) => s.clone(), Value::I(n) => n.to_string(), Value::B(b) => b.to_string() }
      |    }
      |    fn truthy(&self) -> bool {
      |        match self { Value::B(b) => *b, Value::S(s) => !s.is_empty(), Value::I(n) => *n != 0 }
      |    }
      |}
      |fn sig(signals: &HashMap<String, Value>, id: &str) -> String {
      |    signals.get(id).map(|v| v.display()).unwrap_or_default()
      |}
      |fn sig_int(signals: &HashMap<String, Value>, id: &str) -> i64 {
      |    match signals.get(id) { Some(Value::I(n)) => *n, Some(Value::S(s)) => s.parse().unwrap_or(0), Some(Value::B(b)) => if *b { 1 } else { 0 }, None => 0 }
      |}
      |fn sig_truthy(signals: &HashMap<String, Value>, id: &str) -> bool {
      |    signals.get(id).map(|v| v.truthy()).unwrap_or(false)
      |}
      |fn toggle_text(signals: &HashMap<String, Value>, id: &str, label: &str) -> String {
      |    let box_str = if sig_truthy(signals, id) { "[x]" } else { "[ ]" };
      |    if label.is_empty() { box_str.to_string() } else { format!("{} {}", box_str, label) }
      |}
      |fn text_input_display(signals: &HashMap<String, Value>, id: &str, placeholder: &str, secure: bool) -> String {
      |    let v = sig(signals, id);
      |    if v.is_empty() { placeholder.to_string() }
      |    else if secure { "*".repeat(v.chars().count()) }
      |    else { v }
      |}""".stripMargin

  /** Blocking managed-GET runtime: bootstrap once, snapshot each binding's
   *  refresh tick, then refetch only bindings whose tick changed. When there
   *  are no fetches, no-op helpers are emitted (and no `ureq` dependency). */
  private def genFetchHelpers(fetches: mutable.LinkedHashMap[String, FetchInfo]): String =
    if fetches.isEmpty then
      """fn bootstrap(_signals: &mut HashMap<String, Value>) {}
        |fn initial_fetch_ticks(_signals: &HashMap<String, Value>) -> HashMap<String, String> { HashMap::new() }
        |fn refresh_fetches(_signals: &mut HashMap<String, Value>, _observed: &mut HashMap<String, String>) {}""".stripMargin
    else
      // Headers are resolved into a local FIRST: `load_fetch` borrows `signals` mutably and
      // `fetch_headers` borrows it immutably, so passing the call inline would not compile.
      // The URL is either the literal fixed at emit time or, for `fetchUrlSignalTo`, read from a
      // signal HERE — at fetch time — so a picker retargets the GET. Resolved into a local for the
      // same borrow reason the headers are: `load_fetch` takes `signals` mutably.
      def urlExpr(info: FetchInfo): String = info.urlId match
        case None      => rustStr(info.url)
        case Some(uid) => s"&sig(signals, ${rustStr(uid)})"
      // The credential is resolved HERE — at fetch time, on the target — and appended to whatever
      // the headers signal produced. Appended, not prepended: `fetch_text` sets headers in order, so
      // an explicit Authorization already in the signal keeps winning and an app that solved auth
      // its own way does not silently change behaviour.
      def credExpr(info: FetchInfo): String = info.credential match
        case None => ""
        case Some((kind, source, scheme)) =>
          s"if let Some(__a) = resolve_credential(${rustStr(kind)}, ${rustStr(source)}, ${rustStr(scheme)}) " +
          s"{ __h.push((\"Authorization\".to_string(), __a)); } "
      def loadCall(indent: String, id: String, info: FetchInfo): String =
        val body = (info.headersId, info.credential) match
          case (None, None) => s"$indent    load_fetch(signals, ${rustStr(id)}, ${urlExpr(info)}, &[]);"
          case (hid, _) =>
            val base = hid match
              case Some(h) => s"let mut __h = fetch_headers(signals, ${rustStr(h)}); "
              case None    => "let mut __h: Vec<(String, String)> = Vec::new(); "
            s"$indent    $base${credExpr(info)}" +
            s"load_fetch(signals, ${rustStr(id)}, ${urlExpr(info)}, &__h);"
        s"""$indent{
           |$body
           |$indent}""".stripMargin
      val inserts = fetches.map { case (id, info) => loadCall("    ", id, info) }.mkString("\n")
      // What is remembered per fetch is the PAIR (tick, url), not the tick. Remember only the tick
      // and a retarget with an unchanged tick never re-fetches — the bug being fixed, reintroduced
      // one layer down. Remember only the url and a plain refresh stops working.
      def stateExpr(info: FetchInfo): String = info.urlId match
        case None      => s"format!(\"{}\", sig_int(signals, ${rustStr(info.tickId)}))"
        case Some(uid) =>
          s"format!(\"{} {}\", sig_int(signals, ${rustStr(info.tickId)}), sig(signals, ${rustStr(uid)}))"
      val tickSeeds = fetches.map { case (id, info) =>
        s"    observed.insert(${rustStr(id)}.to_string(), ${stateExpr(info)});"
      }.mkString("\n")
      val refreshes = fetches.map { case (id, info) =>
        s"""    {
           |        let current = ${stateExpr(info)};
           |        if observed.get(${rustStr(id)}) != Some(&current) {
           |${loadCall("            ", id, info)}
           |            observed.insert(${rustStr(id)}.to_string(), current);
           |        }
           |    }""".stripMargin
      }.mkString("\n")
      // Emitted only when some fetch binds headers: it references serde_json, which is not a
      // dependency otherwise (see `cargoToml`). A malformed or absent value yields NO headers rather
      // than an error — dropping a header must not turn a working unauthenticated fetch into a
      // failure.
      val headerHelper =
        if !fetches.values.exists(_.headersId.isDefined) then ""
        else
          """fn fetch_headers(signals: &HashMap<String, Value>, id: &str) -> Vec<(String, String)> {
            |    let raw = sig(signals, id);
            |    if raw.is_empty() { return Vec::new(); }
            |    match serde_json::from_str::<serde_json::Value>(&raw) {
            |        Ok(serde_json::Value::Object(map)) => map
            |            .iter()
            |            .map(|(k, v)| match v {
            |                serde_json::Value::String(s) => (k.clone(), s.clone()),
            |                other => (k.clone(), other.to_string()),
            |            })
            |            .collect(),
            |        _ => Vec::new(),
            |    }
            |}
            |""".stripMargin
      s"""${headerHelper}fn fetch_text(url: &str, headers: &[(String, String)]) -> Option<String> {
         |    let mut req = ureq::get(url);
         |    for (name, value) in headers { req = req.set(name, value); }
         |    match req.call() { Ok(resp) => resp.into_string().ok(), Err(_) => None }
         |}
         |fn load_fetch(signals: &mut HashMap<String, Value>, id: &str, url: &str, headers: &[(String, String)]) {
         |    // An empty URL is a picker with nothing selected: make NO request and keep whatever
         |    // was already there, rather than GET "" and blank a populated table.
         |    if url.is_empty() { return; }
         |    if let Some(body) = fetch_text(url, headers) { signals.insert(id.to_string(), Value::S(body)); }
         |}
         |fn bootstrap(signals: &mut HashMap<String, Value>) {
         |$inserts
         |}
         |fn initial_fetch_ticks(signals: &HashMap<String, Value>) -> HashMap<String, String> {
         |    let mut observed = HashMap::new();
         |$tickSeeds
         |    observed
         |}
         |fn refresh_fetches(signals: &mut HashMap<String, Value>, observed: &mut HashMap<String, String>) {
         |$refreshes
         |}""".stripMargin

  /** `fetch_rows` (parse a JSON body → rows × columns) + `json_field` — for
   *  `DataTable.Remote`. The body is fetched into `signals[id]` at bootstrap
   *  (see `collectFetches`) and parsed each frame. Emitted only when a remote
   *  table exists (else no `serde_json` dependency). */
  /** `fetchAction` — the WRITE half. Emitted only when some focusable posts, so an app with only
   *  local handlers keeps its crate free of `ureq`.
   *
   *  The store is mutated ONLY after a 2xx: bumping the tick before the response would refresh a
   *  list that was never written, and clearing the body on a failed send eats the user's message,
   *  which is worse than not sending it. Bumping the tick is also what makes "post, then see the
   *  list update" work with no extra wiring — the tick is a fetch trigger, so the bound GET re-reads
   *  on the next frame. */
  private def genWriteHelpers(fetches: mutable.LinkedHashMap[String, FetchInfo],
                              fs: Seq[Focusable]): String =
    val posts = fs.flatMap(_.activation).collect { case p: Mutation.Post => p }
    if posts.isEmpty then ""
    else
      // `fetch_headers` normally rides along with a header-bound GET. A POST may be the ONLY user of
      // it, in which case genFetchHelpers did not emit it and the crate would not compile.
      val needsHeaderHelper =
        posts.exists(_.headersId.isDefined) && !fetches.values.exists(_.headersId.isDefined)
      val headerHelper =
        if !needsHeaderHelper then ""
        else
          """fn fetch_headers(signals: &HashMap<String, Value>, id: &str) -> Vec<(String, String)> {
            |    let raw = sig(signals, id);
            |    if raw.is_empty() { return Vec::new(); }
            |    match serde_json::from_str::<serde_json::Value>(&raw) {
            |        Ok(serde_json::Value::Object(map)) => map
            |            .iter()
            |            .map(|(k, v)| match v {
            |                serde_json::Value::String(s) => (k.clone(), s.clone()),
            |                other => (k.clone(), other.to_string()),
            |            })
            |            .collect(),
            |        _ => Vec::new(),
            |    }
            |}
            |""".stripMargin
      s"""${headerHelper}fn send_action(signals: &mut HashMap<String, Value>, method: &str, url: &str,
         |                body_id: &str, tick_id: &str, clear_body: bool, headers: &[(String, String)]) {
         |    if url.is_empty() { return; }
         |    let body = sig(signals, body_id);
         |    let mut req = ureq::request(method, url);
         |    req = req.set("Content-Type", "application/json");
         |    for (name, value) in headers { req = req.set(name, value); }
         |    if req.send_string(&body).is_ok() {
         |        let cur = match signals.get(tick_id) { Some(Value::I(n)) => *n, _ => 0 };
         |        signals.insert(tick_id.to_string(), Value::I(cur + 1));
         |        if clear_body { signals.insert(body_id.to_string(), Value::S(String::new())); }
         |    }
         |}""".stripMargin

  /** Row-cursor helpers, emitted only when some table is selectable.
   *
   *  `row_field` re-reads the JSON rather than the already-parsed display rows on purpose: the
   *  field a `RowLink` writes need not be one of the COLUMNS. A room picker shows a name and writes
   *  a URL, which is the whole point of letting the server ship a ready-made value. */
  /** Resolve a declared credential ON THE TARGET, emitted only when some fetch carries one.
   *
   *  The whole point of the declaration is that this function is the only thing that ever holds the
   *  secret: the emitter sees a variable NAME, so there is nothing to bake into the binary.
   *
   *  A missing or unreadable source yields `None`, and the caller then sends NO `Authorization` at
   *  all. An empty `Bearer ` is a header that reads as authentication and is not — a 401 meaning
   *  "no credential" is easier to diagnose than one meaning "bad credential". */
  private def genCredentialHelpers(fetches: mutable.LinkedHashMap[String, FetchInfo], writeCreds: Boolean): String =
    if !fetches.values.exists(_.credential.isDefined) && !writeCreds then ""
    else
      """fn resolve_credential(kind: &str, source: &str, scheme: &str) -> Option<String> {
        |    let secret = match kind {
        |        "env" => std::env::var(source).ok(),
        |        "file" => {
        |            let path = if let Some(rest) = source.strip_prefix("~/") {
        |                match std::env::var("HOME") { Ok(h) => format!("{}/{}", h, rest), Err(_) => source.to_string() }
        |            } else { source.to_string() };
        |            std::fs::read_to_string(path).ok().map(|s| s.trim().to_string())
        |        }
        |        "literal" => Some(source.to_string()),
        |        _ => None,
        |    }?;
        |    if secret.is_empty() { return None; }
        |    Some(format!("{} {}", scheme, secret))
        |}
        |""".stripMargin

  private def genRowCursorHelpers(fs: Seq[Focusable]): String =
    if !fs.exists(_.cursor.isDefined) then ""
    else
      """fn rows_at<'a>(json: &'a serde_json::Value, rows_path: &str) -> Option<&'a Vec<serde_json::Value>> {
        |    let mut cur = json;
        |    if !rows_path.is_empty() {
        |        for part in rows_path.split('.') { cur = cur.get(part)?; }
        |    } else if !cur.is_array() {
        |        for key in ["data", "rows", "items", "results"] {
        |            if let Some(v) = cur.get(key) { cur = v; break; }
        |        }
        |    }
        |    cur.as_array()
        |}
        |fn row_count(json: &str, rows_path: &str) -> usize {
        |    match serde_json::from_str::<serde_json::Value>(json) {
        |        Ok(v) => rows_at(&v, rows_path).map(|r| r.len()).unwrap_or(0),
        |        Err(_) => 0,
        |    }
        |}
        |fn row_field(json: &str, rows_path: &str, index: i64, field: &str) -> Option<String> {
        |    if index < 0 { return None; }
        |    let v = serde_json::from_str::<serde_json::Value>(json).ok()?;
        |    let rows = rows_at(&v, rows_path)?;
        |    let row = rows.get(index as usize)?;
        |    Some(json_field(row, field))
        |}
        |""".stripMargin

  /** Wall-clock advance for `intervalTick` fetches, emitted only when some fetch auto-polls.
   *
   *  Two properties a naive version gets wrong. Ticks are DEDUPLICATED by id: two fetches sharing
   *  one tick are one clock, and bumping per fetch would double the poll rate. And elapsed time is
   *  measured from each tick's LAST fire rather than from process start, so drift is absorbed
   *  instead of accumulating into the period.
   *
   *  The loop polls events with a 100 ms timeout, so a period is a FLOOR, not a guarantee — right
   *  for polling, wrong for a deadline. */
  private def genIntervalHelpers(fetches: mutable.LinkedHashMap[String, FetchInfo]): String =
    val ticks = fetches.values.flatMap(f => f.tickMs.filter(_ > 0).map(ms => (f.tickId, ms)))
      .toSeq.distinctBy(_._1)
    if ticks.isEmpty then
      "fn bump_interval_ticks(_signals: &mut HashMap<String, Value>, _last: &mut HashMap<String, std::time::Instant>) {}"
    else
      val arms = ticks.map { case (id, ms) =>
        s"    { let __e = last.entry(${rustStr(id)}.to_string()).or_insert(now); " +
        s"if now.duration_since(*__e).as_millis() >= ${ms}u128 { *__e = now; " +
        s"let __c = match signals.get(${rustStr(id)}) { Some(Value::I(n)) => *n, _ => 0 }; " +
        s"signals.insert(${rustStr(id)}.to_string(), Value::I(__c + 1)); } }"
      }.mkString("\n")
      "fn bump_interval_ticks(signals: &mut HashMap<String, Value>, last: &mut HashMap<String, std::time::Instant>) {\n" +
      "    let now = std::time::Instant::now();\n" + arms + "\n}"

  private def genTableHelpers(hasRemoteTable: Boolean): String =
    if !hasRemoteTable then ""
    else
      """fn json_field(row: &serde_json::Value, path: &str) -> String {
        |    let mut cur = row;
        |    for part in path.split('.') { cur = match cur.get(part) { Some(x) => x, None => return String::new() }; }
        |    match cur {
        |        serde_json::Value::String(s) => s.clone(),
        |        serde_json::Value::Null => String::new(),
        |        other => other.to_string(),
        |    }
        |}
        |fn row_identity(row: &serde_json::Value, row_key_path: &str) -> Result<String, String> {
        |    if row_key_path.is_empty() || row_key_path.split('.').any(|part| part.is_empty()) {
        |        return Err("rowKeyPath must be a non-empty dotted path".to_string());
        |    }
        |    let mut cur = row;
        |    for part in row_key_path.split('.') {
        |        cur = cur.get(part).ok_or_else(|| format!("missing row key at {}", row_key_path))?;
        |    }
        |    match cur {
        |        serde_json::Value::String(value) if !value.is_empty() => Ok(format!("string:{}", value)),
        |        serde_json::Value::Number(value) if value.as_i64().is_some() || value.as_u64().is_some() => Ok(format!("int:{}", value)),
        |        _ => Err(format!("row key at {} must be a non-empty String or integral number", row_key_path)),
        |    }
        |}
        |fn fetch_rows(json: &str, rows_path: &str, row_key_path: &str, field_paths: &[&str]) -> Result<Vec<Vec<String>>, String> {
        |    use std::collections::HashSet;
        |    let v: serde_json::Value = serde_json::from_str(json).map_err(|_| "table response is not JSON".to_string())?;
        |    let arr_val: &serde_json::Value = if rows_path.is_empty() {
        |        ["data", "rows", "items", "results"].iter().find_map(|k| v.get(k)).unwrap_or(&v)
        |    } else {
        |        let mut cur = &v;
        |        for part in rows_path.split('.') { cur = cur.get(part).ok_or_else(|| format!("missing rowsPath {}", rows_path))?; }
        |        cur
        |    };
        |    let arr = arr_val.as_array().ok_or_else(|| "table rows are not an array".to_string())?;
        |    let mut seen = HashSet::new();
        |    let mut result = Vec::with_capacity(arr.len());
        |    for (index, row) in arr.iter().enumerate() {
        |        let identity = row_identity(row, row_key_path).map_err(|e| format!("row {}: {}", index, e))?;
        |        if !seen.insert(identity.clone()) { return Err(format!("duplicate row key {}", identity)); }
        |        result.push(field_paths.iter().map(|fp| json_field(row, fp)).collect());
        |    }
        |    Ok(result)
        |}""".stripMargin

  private def hasRemoteTable(v: View[?]): Boolean = v match
    case View.DataTable(TableDataSource.Remote(_, _), _, _, _, _) => true
    case View.Column(ch, _, _, _)           => ch.exists(hasRemoteTable)
    case View.Row(ch, _, _, _)              => ch.exists(hasRemoteTable)
    case View.Stack(ch, _)                  => ch.exists(hasRemoteTable)
    case View.Fragment(ch)                  => ch.exists(hasRemoteTable)
    case View.ScrollView(c, _, _)           => hasRemoteTable(c)
    case View.Styled(c, _)                  => hasRemoteTable(c)
    case View.For(items, render)            => items().map(render).exists(hasRemoteTable)
    case View.LazyList(items, render, _, _) => items().map(render).exists(hasRemoteTable)
    case View.Show(cond, t, f)              => hasRemoteTable(if cond() then t() else f())
    case View.ShowSignal(_, t, f)           => hasRemoteTable(t) || hasRemoteTable(f)
    case View.TabBar(tabs, _, _)            => tabs.exists(t => hasRemoteTable(t.content))
    case View.NavigationStack(routes, _, _) => routes.values.exists(r => hasRemoteTable(r()))
    case _                                  => false

  private def emitSignalSeed(signals: mutable.LinkedHashMap[String, SigInfo]): String =
    val sb = StringBuilder()
    signals.foreach { case (id, info) => sb ++= s"    m.insert(${rustStr(id)}.to_string(), ${info.initExpr});\n" }
    sb.toString

  // ── Generated focus + event functions ──────────────────────────────────

  private def genFocusConsts(fs: Seq[Focusable]): String =
    val textIdx = fs.filter(_.textSignalId.isDefined).map(_.idx)
    val isText =
      if textIdx.isEmpty then "fn is_text_input(_focus: usize) -> bool { false }"
      else s"fn is_text_input(focus: usize) -> bool { matches!(focus, ${textIdx.mkString(" | ")}) }"
    // A focused TABLE takes the arrow keys for its row cursor; Tab still moves focus. An app with
    // no selectable table emits the `false` arm and a no-op `move_row`, so it is unchanged.
    val tableIdx = fs.filter(_.cursor.isDefined).map(_.idx)
    val isTable =
      if tableIdx.isEmpty then "fn is_table(_focus: usize) -> bool { false }"
      else s"fn is_table(focus: usize) -> bool { matches!(focus, ${tableIdx.mkString(" | ")}) }"
    val moveArms = fs.filter(_.cursor.isDefined).map { f =>
      val c = f.cursor.get
      // Clamped against the CURRENT row count: a refetch can shrink the data under the cursor.
      s"        ${f.idx} => { let __n = row_count(&sig(signals, ${rustStr(c.fetchId)}), ${rustStr(c.rowsPath)}) as i64; " +
      s"let __cur = match signals.get(${rustStr(c.cursorId)}) { Some(Value::I(n)) => *n, _ => 0 }; " +
      s"let __next = if __n == 0 { 0 } else { (__cur + delta).clamp(0, __n - 1) }; " +
      s"signals.insert(${rustStr(c.cursorId)}.to_string(), Value::I(__next)); }"
    }
    val moveRow =
      if moveArms.isEmpty then "fn move_row(_focus: usize, _signals: &mut HashMap<String, Value>, _delta: i64) {}"
      else "fn move_row(focus: usize, signals: &mut HashMap<String, Value>, delta: i64) {\n" +
           "    match focus {\n" + moveArms.mkString("\n") + "\n        _ => {}\n    }\n}"
    s"""const FOCUS_COUNT: usize = ${fs.size};
       |fn focus_mark(focus: usize, idx: usize) -> &'static str { if focus == idx { "> " } else { "  " } }
       |$isText
       |$isTable
       |$moveRow""".stripMargin

  private def mutationRust(m: Mutation): String = m match
    case Mutation.Set(id, vexpr) => s"signals.insert(${rustStr(id)}.to_string(), $vexpr);"
    case Mutation.Incr(id, by)   =>
      s"let cur = match signals.get(${rustStr(id)}) { Some(Value::I(n)) => *n, _ => 0 }; signals.insert(${rustStr(id)}.to_string(), Value::I(cur + $by));"
    case Mutation.Toggle(id)     =>
      s"let cur = sig_truthy(signals, ${rustStr(id)}); signals.insert(${rustStr(id)}.to_string(), Value::B(!cur));"
    case Mutation.PickRow(cursorId, fetchId, rowsPath, fieldPath, targetId) =>
      // Over an empty list this writes NOTHING: a picker with no rows must not blank a
      // selection that already exists.
      s"let __i = match signals.get(${rustStr(cursorId)}) { Some(Value::I(n)) => *n, _ => 0 }; " +
      s"if let Some(__v) = row_field(&sig(signals, ${rustStr(fetchId)}), ${rustStr(rowsPath)}, __i, ${rustStr(fieldPath)}) " +
      s"{ signals.insert(${rustStr(targetId)}.to_string(), Value::S(__v)); }"
    case Mutation.Post(method, url, bodyId, tickId, clearBody, headersId, cred) =>
      // One helper call rather than an inlined request: the arms are single-expression blocks.
      // The credential is resolved on the target and appended, exactly as on the read path — a
      // client that declares its GETs and bakes a header for its POSTs still ships the token.
      val hdrs = headersId match
        case None      => "Vec::new()"
        case Some(hid) => s"fetch_headers(signals, ${rustStr(hid)})"
      val credPush = cred match
        case None => ""
        case Some((kind, source, scheme)) =>
          s"if let Some(__a) = resolve_credential(${rustStr(kind)}, ${rustStr(source)}, ${rustStr(scheme)}) " +
          s"{ __h.push((\"Authorization\".to_string(), __a)); } "
      s"let mut __h = $hdrs; $credPush" +
      s"send_action(signals, ${rustStr(method)}, ${rustStr(url)}, " +
      s"${rustStr(bodyId)}, ${rustStr(tickId)}, $clearBody, &__h);"

  private def genActivate(fs: Seq[Focusable]): String =
    val arms = fs.collect { case f if f.activation.isDefined =>
      s"        ${f.idx} => { ${mutationRust(f.activation.get)} }"
    }
    s"""fn activate(focus: usize, signals: &mut HashMap<String, Value>) {
       |    match focus {
       |${arms.mkString("\n")}
       |        _ => {}
       |    }
       |}""".stripMargin

  private def genTypeChar(fs: Seq[Focusable]): String =
    val arms = fs.collect { case f if f.textSignalId.isDefined =>
      val id = f.textSignalId.get
      s"        ${f.idx} => { let mut s = sig(signals, ${rustStr(id)}); s.push(c); signals.insert(${rustStr(id)}.to_string(), Value::S(s)); }"
    }
    s"""fn type_char(focus: usize, signals: &mut HashMap<String, Value>, c: char) {
       |    match focus {
       |${arms.mkString("\n")}
       |        _ => {}
       |    }
       |}""".stripMargin

  private def genBackspace(fs: Seq[Focusable]): String =
    val arms = fs.collect { case f if f.textSignalId.isDefined =>
      val id = f.textSignalId.get
      s"        ${f.idx} => { let mut s = sig(signals, ${rustStr(id)}); s.pop(); signals.insert(${rustStr(id)}.to_string(), Value::S(s)); }"
    }
    s"""fn backspace(focus: usize, signals: &mut HashMap<String, Value>) {
       |    match focus {
       |${arms.mkString("\n")}
       |        _ => {}
       |    }
       |}""".stripMargin

  private def genHandleKey(): String =
    """fn handle_key(code: KeyCode, signals: &mut HashMap<String, Value>, focus: &mut usize) -> bool {
      |    match code {
      |        KeyCode::Esc => return true,
      |        // Arrows belong to the FOCUSED WIDGET when that widget is a table; Tab is the
      |        // canonical focus key and always moves focus. With no selectable table `is_table`
      |        // is a constant `false`, so this is the previous behaviour exactly.
      |        KeyCode::Down if is_table(*focus) => move_row(*focus, signals, 1),
      |        KeyCode::Up   if is_table(*focus) => move_row(*focus, signals, -1),
      |        KeyCode::Tab | KeyCode::Down => { if FOCUS_COUNT > 0 { *focus = (*focus + 1) % FOCUS_COUNT; } }
      |        KeyCode::BackTab | KeyCode::Up => { if FOCUS_COUNT > 0 { *focus = (*focus + FOCUS_COUNT - 1) % FOCUS_COUNT; } }
      |        KeyCode::Enter => activate(*focus, signals),
      |        KeyCode::Backspace if is_text_input(*focus) => backspace(*focus, signals),
      |        KeyCode::Char(c) => {
      |            if is_text_input(*focus) { type_char(*focus, signals, c); }
      |            else if c == ' ' { activate(*focus, signals); }
      |            else if c == 'q' { return true; }
      |        }
      |        _ => {}
      |    }
      |    false
      |}""".stripMargin

  /** `#[cfg(test)]` self-tests: reactivity (text signal present), event
   *  handlers run (a mutating focusable present), text typing (a text input
   *  present), focus traversal (any focusable present). */
  private def genTests(
      signals: mutable.LinkedHashMap[String, SigInfo],
      fs: Seq[Focusable],
      hasRemoteTable: Boolean): String =
    val tests = mutable.ArrayBuffer.empty[String]
    if hasRemoteTable then
      tests +=
        """    #[test]
          |    fn datatable_row_identity_contract() {
          |        let fields = ["id"];
          |        assert!(fetch_rows(r#"{"data":[{"id":1},{"id":"1"}]}"#, "data", "id", &fields).is_ok());
          |        for invalid in [
          |            r#"{"data":[{}]}"#,
          |            r#"{"data":[{"id":""}]}"#,
          |            r#"{"data":[{"id":{"nested":1}}]}"#,
          |            r#"{"data":[{"id":1},{"id":1}]}"#,
          |            r#"{"data":[[]]}"#,
          |        ] {
          |            assert!(fetch_rows(invalid, "data", "id", &fields).is_err(), "accepted {}", invalid);
          |        }
          |    }""".stripMargin
    signals.collectFirst { case (id, info) if info.isText => id }.foreach { id =>
      tests += s"""    #[test]
                  |    fn reactive_rerender() {
                  |        let mut signals = initial_signals();
                  |        let before = render_to_string(80, 24, &signals, 0);
                  |        signals.insert(${rustStr(id)}.to_string(), Value::S("SSC_RERENDER_SENTINEL".to_string()));
                  |        let after = render_to_string(80, 24, &signals, 0);
                  |        assert_ne!(before, after, "signal change did not re-render");
                  |        assert!(after.contains("SSC_RERENDER_SENTINEL"), "new value not rendered");
                  |    }""".stripMargin
    }
    fs.collectFirst { case f if f.activation.isDefined => f.idx }.foreach { idx =>
      tests += s"""    #[test]
                  |    fn event_handlers_run() {
                  |        let mut signals = initial_signals();
                  |        let before = signals.clone();
                  |        activate($idx, &mut signals);
                  |        assert_ne!(before, signals, "activate did not mutate the store");
                  |    }""".stripMargin
    }
    fs.collectFirst { case f if f.textSignalId.isDefined => (f.idx, f.textSignalId.get) }.foreach { case (idx, id) =>
      tests += s"""    #[test]
                  |    fn text_input_typing() {
                  |        let mut signals = initial_signals();
                  |        type_char($idx, &mut signals, 'X');
                  |        assert!(sig(&signals, ${rustStr(id)}).contains('X'), "typed char not in signal");
                  |    }""".stripMargin
    }
    if fs.nonEmpty then
      tests += s"""    #[test]
                  |    fn tab_moves_focus() {
                  |        let mut signals = initial_signals();
                  |        let mut focus = 0usize;
                  |        handle_key(KeyCode::Tab, &mut signals, &mut focus);
                  |        assert_eq!(focus, ${if fs.sizeIs > 1 then 1 else 0});
                  |    }""".stripMargin
    if tests.isEmpty then ""
    else
      s"""
         |#[cfg(test)]
         |mod tests {
         |    use super::*;
         |${tests.mkString("\n")}
         |}
         |""".stripMargin

  // ── Signal collection ──────────────────────────────────────────────────

  private def collectSignals(v: View[?], acc: mutable.LinkedHashMap[String, SigInfo]): Unit =
    def add(id: String, init: String, isText: Boolean): Unit =
      if !acc.contains(id) then acc(id) = SigInfo(init, isText)
    v match
      case View.SignalText(s, _)               => add(s.id, valueExpr(safeApply(s)), isText = true)
      case View.TextInput(s, _, _, _, _)       => add(s.id, valueExpr(safeApply(s)), isText = true)
      case View.Toggle(c, _, _)                => add(c.id, s"Value::B(${safeBool(c)})", isText = false)
      case View.ShowSignal(c, t, f)            => add(c.id, s"Value::B(${safeBool(c)})", isText = false); collectSignals(t, acc); collectSignals(f, acc)
      case View.Column(ch, _, _, _)            => ch.foreach(collectSignals(_, acc))
      case View.Row(ch, _, _, _)               => ch.foreach(collectSignals(_, acc))
      case View.Stack(ch, _)                   => ch.foreach(collectSignals(_, acc))
      case View.Fragment(ch)                   => ch.foreach(collectSignals(_, acc))
      case View.ScrollView(c, _, _)            => collectSignals(c, acc)
      case View.Styled(c, _)                   => collectSignals(c, acc)
      case View.For(items, render)             => items().map(render).foreach(collectSignals(_, acc))
      case View.LazyList(items, render, _, _)  => items().map(render).foreach(collectSignals(_, acc))
      case View.Show(cond, t, f)               => collectSignals(if cond() then t() else f(), acc)
      case View.Button(label, action, _, _)    => collectSignals(label, acc); collectHandlerSignal(action, add)
      // A RowLink's TARGET is reachable only through the action list — no View node renders it — so
      // without this it is never seeded and starts life empty. That is the same hole the composer's
      // draft signal fell into, and it is invisible until something actually reads the value.
      case View.DataTable(_, _, actions, _, _)  =>
        actions.foreach {
          case RowActionDef.RowLink(_, sig, _) => add(sig.id, valueExpr(safeApply(sig)), true)
          case _                               => ()
        }
      case View.TabBar(tabs, current, _)       => add(current.id, valueExpr(safeApply(current)), false); tabs.foreach(t => collectSignals(t.content, acc))
      case View.NavigationStack(routes, current, _) => add(current.id, valueExpr(safeApply(current)), false); routes.values.foreach(r => collectSignals(r(), acc))
      case _                                   => ()

  private def collectHandlerSignal(h: EventHandler, add: (String, String, Boolean) => Unit): Unit = h match
    case EventHandler.SetSignalLiteral(s, _) => add(s.id, valueExpr(safeApply(s)), false)
    case EventHandler.IncrementSignal(s, _)  => add(s.id, valueExpr(safeApply(s)), false)
    case EventHandler.ToggleSignal(s)        => add(s.id, s"Value::B(${safeBool(s)})", false)
    // A composer's body, tick and headers are reachable ONLY through its handler — no View node
    // mentions them. Without this they are never seeded into `initial_signals`, so `sig()` returns
    // "" and the POST goes out with an empty body while still reporting success. Found by the cargo
    // gate; no string assertion on the emitted call site can see it.
    case EventHandler.FetchAction(_, _, body, tick, _, headers, _) =>
      add(body.id, valueExpr(safeApply(body)), true)
      add(tick.id, valueExpr(safeApply(tick)), false)
      headers.foreach(h => add(h.id, valueExpr(safeApply(h)), true))
    case _                                   => ()

  /** Emit-time signal reads can throw — a `computedSignal(() => …)` over a
   *  not-yet-fetched value (e.g. `jsonValue(fetch())` on an empty body). The
   *  TUI seeds the store with the static snapshot, so guard against a throw
   *  (a derived value just seeds empty/false until it's fetched/recomputed). */
  private def safeApply(s: ReactiveSignal[?]): Any =
    try s() catch case _: Throwable => ""
  private def safeBool(s: ReactiveSignal[?]): Boolean =
    try s() match { case b: Boolean => b; case _ => false } catch case _: Throwable => false

  /** Collect fetch-bound signals (`FetchUrlSignal`) → managed GET metadata so
   *  bootstrap can load them and the event loop can honor `tickId` changes. */
  private def collectFetches(v: View[?], fetches: mutable.LinkedHashMap[String, FetchInfo]): Unit =
    def rec(c: View[?]): Unit = collectFetches(c, fetches)
    def record(s: ReactiveSignal[?]): Unit = s match
      case f: FetchUrlSignal => if !fetches.contains(f.id) then fetches(f.id) = FetchInfo(f.fetchUrl, f.tickId, f.headersId, f.urlId, f.tickMs, f.credential)
      case _                 => ()
    v match
      case View.SignalText(s, _)                                 => record(s)
      case View.DataTable(TableDataSource.Remote(s, _), _, _, _, _) => record(s)
      case View.ModelView(s, _, t, _)                            => record(s); rec(t)
      case View.Column(ch, _, _, _)                              => ch.foreach(rec)
      case View.Row(ch, _, _, _)                                 => ch.foreach(rec)
      case View.Stack(ch, _)                                     => ch.foreach(rec)
      case View.Fragment(ch)                                     => ch.foreach(rec)
      case View.ScrollView(c, _, _)                              => rec(c)
      case View.Styled(c, _)                                     => rec(c)
      case View.For(items, render)                               => items().map(render).foreach(rec)
      case View.LazyList(items, render, _, _)                    => items().map(render).foreach(rec)
      case View.Show(cond, t, f)                                 => rec(if cond() then t() else f())
      case View.ShowSignal(_, t, f)                              => rec(t); rec(f)
      case View.Button(label, _, _, _)                           => rec(label)
      case View.TabBar(tabs, _, _)                               => tabs.foreach(t => rec(t.content))
      case View.NavigationStack(routes, _, _)                    => routes.values.foreach(r => rec(r()))
      case _                                                     => ()

  private def valueExpr(v: Any): String = v match
    case b: Boolean => s"Value::B($b)"
    case i: Int     => s"Value::I($i)"
    case l: Long    => s"Value::I($l)"
    case other      => s"Value::S(${rustStr(String.valueOf(other))}.to_string())"

  private def activationOf(h: EventHandler): Option[Mutation] = h match
    case EventHandler.SetSignalLiteral(s, value) => Some(Mutation.Set(s.id, valueExpr(value)))
    case EventHandler.IncrementSignal(s, by)     => Some(Mutation.Incr(s.id, by))
    case EventHandler.ToggleSignal(s)            => Some(Mutation.Toggle(s.id))
    case EventHandler.FetchAction(method, url, body, tick, clearBody, headers, cred) =>
      Some(Mutation.Post(method, url, body.id, tick.id, clearBody, headers.map(_.id), cred))
    case _                                       => None

  // ── View → ratatui lowering ────────────────────────────────────────────

  private enum Dir { case Vertical, Horizontal }

  private def emit(view: View[?], area: String, sb: StringBuilder, ids: Iterator[Int], fs: mutable.ArrayBuffer[Focusable], st: TermStyle): Unit =
    view match
      case View.Column(children, _, _, _)     => emitStack(children, area, Dir.Vertical, sb, ids, fs, st)
      case View.Fragment(children)            => emitStack(children, area, Dir.Vertical, sb, ids, fs, st)
      case View.For(items, render)            => emitStack(items().map(render), area, Dir.Vertical, sb, ids, fs, st)
      case View.LazyList(items, render, _, _) => emitStack(items().map(render), area, Dir.Vertical, sb, ids, fs, st)
      case View.Row(children, _, _, _)        => emitStack(children, area, Dir.Horizontal, sb, ids, fs, st)
      case View.Stack(children, _)            => children.foreach(c => emit(c, area, sb, ids, fs, st))
      case View.ScrollView(child, _, _)       => emit(child, area, sb, ids, fs, st)
      case View.Styled(child, style)          => emit(child, area, sb, ids, fs, st.merge(termStyleOf(style)))
      case View.Show(cond, t, f)              => emit(if cond() then t() else f(), area, sb, ids, fs, st)
      case View.ShowSignal(cond, t, f) =>
        sb ++= s"    if sig_truthy(signals, ${rustStr(cond.id)}) {\n"
        emit(t, area, sb, ids, fs, st)
        sb ++= "    } else {\n"
        emit(f, area, sb, ids, fs, st)
        sb ++= "    }\n"
      case View.Text(content, style)          => para(rustStr(content()), area, sb, st.merge(termStyleOf(style)), None)
      case View.TextNode(value)               => para(rustStr(value()), area, sb, st, None)
      case View.SignalText(signal, style)     => para(s"sig(signals, ${rustStr(signal.id)})", area, sb, st.merge(termStyleOf(style)), None)
      case View.Button(label, action, _, style) =>
        val idx = fs.size
        fs += Focusable(idx, activationOf(action), None)
        para(s"""format!("{}[{}]", focus_mark(focus, $idx), ${rustStr(staticText(label))})""", area, sb, st.merge(termStyleOf(style)), Some(idx))
      case View.Toggle(checked, label, style) =>
        val idx = fs.size
        fs += Focusable(idx, Some(Mutation.Toggle(checked.id)), None)
        para(s"""format!("{}{}", focus_mark(focus, $idx), toggle_text(signals, ${rustStr(checked.id)}, ${rustStr(label)}))""", area, sb, st.merge(termStyleOf(style)), Some(idx))
      case View.TextInput(value, placeholder, _, secure, style) =>
        val idx = fs.size
        fs += Focusable(idx, None, Some(value.id))
        para(s"""format!("{}{}", focus_mark(focus, $idx), text_input_display(signals, ${rustStr(value.id)}, ${rustStr(placeholder)}, $secure))""", area, sb, st.merge(termStyleOf(style)), Some(idx))
      case View.DataTable(source, columns, actions, _, rowKeyPath) =>
        // A RowLink means "choosing this row writes row[fieldPath] into signal" — the web target
        // lowers it to a per-row button. Here the TABLE is one focusable with a row cursor: row
        // count is a runtime property of fetched data while the focus ring is emitted at build
        // time, so per-row focusables cannot exist.
        val rowLink = actions.collectFirst { case RowActionDef.RowLink(_, sig, field) => (sig, field) }
        source match
          case TableDataSource.StaticRows(rows) =>
            validateStaticRowKeys(rows, rowKeyPath)
            val n = math.max(1, columns.size)
            val widths = (0 until n).map(_ => s"Constraint::Ratio(1, $n)").mkString(", ")
            val header = columns.map(c => rustStr(c.title)).mkString(", ")
            val rowExprs = rows.map { row =>
              val cells = columns.map(c => rustStr(String.valueOf(row.getOrElse(c.fieldPath, "")))).mkString(", ")
              s"Row::new(vec![$cells])"
            }.mkString(", ")
            sb ++= s"    { let __rows = vec![$rowExprs]; let __t = Table::new(__rows, [$widths]).header(Row::new(vec![$header])); frame.render_widget(__t, $area); }\n"
          case TableDataSource.Remote(fetchSig, rowsPath) =>
            // Managed GET body lives in signals[id] and is parsed each frame.
            val n = math.max(1, columns.size)
            val widths = (0 until n).map(_ => s"Constraint::Ratio(1, $n)").mkString(", ")
            val header = columns.map(c => rustStr(c.title)).mkString(", ")
            val fields = columns.map(c => rustStr(c.fieldPath)).mkString(", ")
            val common = s"    { let __json = sig(signals, ${rustStr(fetchSig.id)}); " +
                   s"let __rows = fetch_rows(&__json, ${rustStr(rowsPath)}, ${rustStr(rowKeyPath)}, &[$fields]).expect(\"invalid DataTable row identity\"); " +
                   s"let __trows: Vec<Row> = __rows.iter().map(|r| Row::new(r.iter().cloned().collect::<Vec<String>>())).collect(); " +
                   s"let __t = Table::new(__trows, [$widths]).header(Row::new(vec![$header]))"
            rowLink match
              case None =>
                sb ++= common + s"; frame.render_widget(__t, $area); }\n"
              case Some((target, fieldPath)) =>
                val fidx = fs.size
                val cursorId = s"${fetchSig.id}__row"
                fs += Focusable(fidx,
                  Some(Mutation.PickRow(cursorId, fetchSig.id, rowsPath, fieldPath, target.id)), None,
                  Some(TableCursor(cursorId, fetchSig.id, rowsPath, fieldPath, target.id)))
                // A REAL ratatui selection rather than a marker glued into a cell, so what is
                // highlighted cannot disagree with what `Enter` writes. Clamped here too: the frame
                // after a shrinking refetch renders before any key is pressed.
                sb ++= common +
                  s".row_highlight_style(Style::default().add_modifier(Modifier::REVERSED)); " +
                  s"let __sel = if __rows.is_empty() { 0usize } else { (match signals.get(${rustStr(cursorId)}) { Some(Value::I(n)) => *n, _ => 0 }).clamp(0, __rows.len() as i64 - 1) as usize }; " +
                  s"let mut __st = ratatui::widgets::TableState::default(); " +
                  s"if !__rows.is_empty() && focus == $fidx { __st.select(Some(__sel)); } " +
                  s"frame.render_stateful_widget(__t, $area, &mut __st); }\n"
          case _ =>
            para(rustStr("(table: signal-row source — follow-up)"), area, sb, st, None)
      case View.TabBar(tabs, current, _) =>
        val outer = s"tabs${ids.next()}"
        sb ++= s"    let $outer = Layout::vertical([Constraint::Length(1), Constraint::Min(0)]).split($area);\n"
        val hdr = s"tabhdr${ids.next()}"
        val n = math.max(1, tabs.size)
        val hw = (0 until n).map(_ => s"Constraint::Ratio(1, $n)").mkString(", ")
        sb ++= s"    let $hdr = Layout::horizontal([$hw]).split($outer[0]);\n"
        tabs.zipWithIndex.foreach { case (t, i) =>
          val fidx = fs.size
          fs += Focusable(fidx, Some(Mutation.Set(current.id, s"Value::I($i)")), None)
          val active   = rustStr("[" + t.label + "]")
          val inactive = rustStr(" " + t.label + " ")
          sb ++= s"""    frame.render_widget(Paragraph::new(format!("{}{}", focus_mark(focus, $fidx), if sig_int(signals, ${rustStr(current.id)}) == $i { $active } else { $inactive })).style(if focus == $fidx { Style::default().add_modifier(Modifier::REVERSED) } else { Style::default() }), $hdr[$i]);\n"""
        }
        sb ++= s"    match sig_int(signals, ${rustStr(current.id)}) {\n"
        tabs.zipWithIndex.foreach { case (t, i) =>
          sb ++= s"        $i => {\n"
          emit(t.content, s"$outer[1]", sb, ids, fs, st)
          sb ++= "        }\n"
        }
        sb ++= "        _ => {}\n    }\n"
      case View.NavigationStack(routes, current, _) =>
        sb ++= s"    match sig(signals, ${rustStr(current.id)}).as_str() {\n"
        routes.foreach { case (name, viewThunk) =>
          sb ++= s"        ${rustStr(name)} => {\n"
          emit(viewThunk(), area, sb, ids, fs, st)
          sb ++= "        }\n"
        }
        sb ++= "        _ => {}\n    }\n"
      case View.Divider(_, _) =>
        sb ++= s"    frame.render_widget(Block::new().borders(Borders::TOP), $area);\n"
      case View.Spacer(_) => ()
      case _              => ()

  private def emitStack(children: Seq[View[?]], area: String, dir: Dir, sb: StringBuilder, ids: Iterator[Int], fs: mutable.ArrayBuffer[Focusable], st: TermStyle): Unit =
    val kids = children.filterNot(isEmpty)
    if kids.isEmpty then ()
    else if kids.sizeIs == 1 then emit(kids.head, area, sb, ids, fs, st)
    else
      val chunks = s"chunks${ids.next()}"
      val constraints = dir match
        // A child whose height cannot be known at emit time takes the REMAINING space instead of a
        // fixed line count. A remote table is the case that matters: its row count is genuinely
        // unknowable here, and answering that with `Length(1)` gave it exactly one line — the header
        // rendered and not a single row did, with the fetch working and the data in the store. Every
        // other child keeps the constraint it had, or existing layouts would shift.
        case Dir.Vertical   => kids.map(k =>
          if isFlexibleHeight(k) then s"Constraint::Min(${measureHeight(k)})"
          else s"Constraint::Length(${measureHeight(k)})").mkString(", ")
        case Dir.Horizontal => kids.map(_ => s"Constraint::Ratio(1, ${kids.size})").mkString(", ")
      val ctor = dir match { case Dir.Vertical => "vertical"; case Dir.Horizontal => "horizontal" }
      sb ++= s"    let $chunks = Layout::$ctor([$constraints]).split($area);\n"
      kids.zipWithIndex.foreach { case (k, i) => emit(k, s"$chunks[$i]", sb, ids, fs, st) }

  /** Emit `frame.render_widget(Paragraph::new(<expr>)[.style(...)], <area>)`.
   *  A focusable leaf (`focusIdx`) gets `Modifier::REVERSED` when focused, so
   *  the focused widget is visibly highlighted on top of its own style. */
  private def para(expr: String, area: String, sb: StringBuilder, st: TermStyle, focusIdx: Option[Int]): Unit =
    val styleClause = focusIdx match
      case Some(idx) =>
        val base = if st.isEmpty then "Style::default()" else st.rustExpr
        s".style(if focus == $idx { $base.add_modifier(Modifier::REVERSED) } else { $base })"
      case None =>
        if st.isEmpty then "" else s".style(${st.rustExpr})"
    sb ++= s"    frame.render_widget(Paragraph::new($expr)$styleClause, $area);\n"

  // ── Style → ratatui terminal style ─────────────────────────────────────

  /** The terminal-renderable subset of a `Style`: fg/bg colors (as ratatui
   *  `Color` expressions) + bold/dim/underline modifiers. */
  private final case class TermStyle(fg: Option[String], bg: Option[String], bold: Boolean, dim: Boolean, underline: Boolean):
    def isEmpty: Boolean = fg.isEmpty && bg.isEmpty && !bold && !dim && !underline
    /** Child wins on colors; modifiers accumulate. */
    def merge(c: TermStyle): TermStyle =
      TermStyle(c.fg.orElse(fg), c.bg.orElse(bg), bold || c.bold, dim || c.dim, underline || c.underline)
    def rustExpr: String =
      val b = StringBuilder("Style::default()")
      fg.foreach(c => b ++= s".fg($c)")
      bg.foreach(c => b ++= s".bg($c)")
      val mods = List(
        if bold then "Modifier::BOLD" else "",
        if dim then "Modifier::DIM" else "",
        if underline then "Modifier::UNDERLINED" else "",
      ).filter(_.nonEmpty)
      if mods.nonEmpty then b ++= s".add_modifier(${mods.mkString(" | ")})"
      b.toString

  private object TermStyle:
    val empty: TermStyle = TermStyle(None, None, false, false, false)

  private def termStyleOf(s: Style): TermStyle =
    TermStyle(
      fg        = s.text.foreground.flatMap(colorExpr),
      bg        = s.decoration.background.flatMap(colorExpr),
      bold      = s.text.fontWeight.exists(isBoldWeight),
      dim       = s.text.fontWeight.exists(isDimWeight),
      underline = s.text.textDecoration.contains(TextDecoration.Underline),
    )

  private def isBoldWeight(w: FontWeight): Boolean = w match
    case FontWeight.SemiBold | FontWeight.Bold | FontWeight.ExtraBold | FontWeight.Black => true
    case FontWeight.Custom(v) => v >= 600
    case _ => false

  private def isDimWeight(w: FontWeight): Boolean = w match
    case FontWeight.Thin | FontWeight.ExtraLight | FontWeight.Light => true
    case _ => false

  /** Map a `Color` to a ratatui `Color` expression, or `None` to leave the
   *  default (e.g. semantic tokens we don't resolve, or `Transparent`). */
  private def colorExpr(c: Color): Option[String] = c match
    case Color.Rgb(r, g, b)     => Some(s"Color::Rgb($r, $g, $b)")
    case Color.Rgba(r, g, b, _) => Some(s"Color::Rgb($r, $g, $b)")
    case Color.Hex(v)           => hexToRgb(v).map((r, g, b) => s"Color::Rgb($r, $g, $b)")
    case Color.Named(name)      => namedColor(name.trim.toLowerCase)
    case Color.System(token)    => systemColor(token.trim.toLowerCase)
    case Color.Transparent      => None

  private def hexToRgb(v: String): Option[(Int, Int, Int)] =
    val h = v.trim.stripPrefix("#")
    if h.length == 6 && h.forall(isHexDigit) then
      Some((Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)))
    else None

  private def isHexDigit(ch: Char): Boolean = ch.isDigit || ('a' to 'f').contains(ch.toLower)

  private def namedColor(name: String): Option[String] = name match
    case "black"   => Some("Color::Black")
    case "red"     => Some("Color::Red")
    case "green"   => Some("Color::Green")
    case "yellow"  => Some("Color::Yellow")
    case "blue"    => Some("Color::Blue")
    case "magenta" | "purple" => Some("Color::Magenta")
    case "cyan"    => Some("Color::Cyan")
    case "white"   => Some("Color::White")
    case "gray" | "grey" => Some("Color::Gray")
    case "darkgray" | "darkgrey" => Some("Color::DarkGray")
    case _         => None

  private def systemColor(token: String): Option[String] = token match
    case "error" | "ondanger"     => Some("Color::Red")
    case "primary" | "accent"     => Some("Color::Cyan")
    case "secondary"              => Some("Color::Magenta")
    case "muted" | "border"       => Some("Color::DarkGray")
    case "foreground" | "onprimary" => Some("Color::White")
    case _                        => None

  private def isEmpty(v: View[?]): Boolean = v match
    case View.Fragment(ch) => ch.forall(isEmpty)
    case _                 => false

  /** True when a node's height is unknowable at emit time and it should take the remaining space.
   *  Only a REMOTE table qualifies today — a `StaticRows` table's height is already computed, and
   *  making anything else flex would move every existing layout. */
  private def isFlexibleHeight(v: View[?]): Boolean = v match
    case View.DataTable(TableDataSource.StaticRows(_), _, _, _, _) => false
    case View.DataTable(_, _, _, _, _)                             => true
    case View.Styled(child, _)                                     => isFlexibleHeight(child)
    case View.ScrollView(child, _, _)                              => isFlexibleHeight(child)
    case _                                                         => false

  private def measureHeight(v: View[?]): Int = v match
    case View.Column(ch, _, _, _)           => ch.filterNot(isEmpty).map(measureHeight).sum
    case View.Fragment(ch)                  => ch.filterNot(isEmpty).map(measureHeight).sum
    case View.For(items, render)            => items().map(render).map(measureHeight).sum
    case View.LazyList(items, render, _, _) => items().map(render).map(measureHeight).sum
    case View.Row(ch, _, _, _)              => ch.filterNot(isEmpty).map(measureHeight).maxOption.getOrElse(1)
    case View.Stack(ch, _)                  => ch.map(measureHeight).maxOption.getOrElse(1)
    case View.ScrollView(child, _, _)       => measureHeight(child)
    case View.Styled(child, _)              => measureHeight(child)
    case View.Show(cond, t, f)              => measureHeight(if cond() then t() else f())
    case View.ShowSignal(_, t, f)           => math.max(measureHeight(t), measureHeight(f))
    case View.TabBar(tabs, _, _)            => 1 + tabs.map(t => measureHeight(t.content)).maxOption.getOrElse(0)
    case View.NavigationStack(routes, _, _) => routes.values.map(r => measureHeight(r())).maxOption.getOrElse(1)
    case View.DataTable(source, columns, _, _, _) => source match
      case TableDataSource.StaticRows(rows) => rows.size + 1
      // A floor, not a guess: header + two rows, so a picker in a cramped column is usable rather
      // than technically-correct-and-empty. The real size comes from `Constraint::Min` taking what
      // is left (see `emitStack`).
      case _                                => 3
    case View.Spacer(size)                  => math.max(0, size.map(_.round.toInt).getOrElse(1))
    case _                                  => 1

  private def staticText(v: View[?]): String = v match
    case View.Text(content, _)       => content()
    case View.TextNode(value)        => value()
    case View.SignalText(signal, _)  => String.valueOf(signal())
    case View.Fragment(ch)           => ch.map(staticText).mkString
    case View.Styled(child, _)       => staticText(child)
    case View.Element(_, _, _, ch)   => ch.map(staticText).mkString
    case View.Button(label, _, _, _) => staticText(label)
    case _                           => ""

  private def rustStr(s: String): String =
    val esc = s.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch   => ch.toString
    }
    "\"" + esc + "\""

  private def validateStaticRowKeys(rows: List[Map[String, Any]], rowKeyPath: String): Unit =
    val path = if rowKeyPath.isEmpty then "id" else rowKeyPath
    if path.split("\\.", -1).exists(_.isEmpty) then
      throw IllegalArgumentException("DataTable rowKeyPath must be a non-empty dotted path")
    def lookup(value: Any, parts: List[String]): Option[Any] = parts match
      case Nil => Some(value)
      case head :: tail => value match
        case map: collection.Map[?, ?] =>
          map.asInstanceOf[collection.Map[Any, Any]].get(head).flatMap(lookup(_, tail))
        case _ => None
    val seen = collection.mutable.HashSet.empty[String]
    rows.zipWithIndex.foreach { case (row, index) =>
      val value = lookup(row, path.split("\\.").toList).getOrElse(
        throw IllegalArgumentException(s"DataTable row $index is missing key at $path"))
      val identity = value match
        case text: String if text.nonEmpty => s"string:$text"
        case value: Byte => s"int:$value"
        case value: Short => s"int:$value"
        case value: Int => s"int:$value"
        case value: Long => s"int:$value"
        case value: BigInt => s"bigint:$value"
        case value: java.math.BigInteger => s"bigint:$value"
        case _ => throw IllegalArgumentException(
          s"DataTable row $index key at $path must be a non-empty String or integral scalar")
      if !seen.add(identity) then
        throw IllegalArgumentException(s"DataTable duplicate row key $identity")
    }

  private def crateName(manifest: AppManifest): String =
    val seg = manifest.bundleId.split('.').lastOption.getOrElse("").trim
    val safe = seg.toLowerCase.map(c => if c.isLetterOrDigit then c else '-').dropWhile(!_.isLetter)
    if safe.nonEmpty then safe else "ssc-tui-app"
