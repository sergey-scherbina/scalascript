package scalascript.frontend.tui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import java.nio.file.{Files, Path}
import scala.sys.process.*

/** End-to-end cargo gates for the FETCH capabilities of the terminal target — the ones a
 *  string-matching emitter test cannot prove, because it never compiles or runs what it asserts
 *  about. Reported by rozum as `tui-fetch-url-signal` and `tui-fetch-post`; specs
 *  `specs/frontend-tui-fetch-url-signal.md`, `specs/frontend-tui-fetch-post.md`.
 *
 *  Separate from [[TuiCargoSmokeTest]] on purpose. That file is held by `tui-widget-compile-coverage`,
 *  whose task is adding compile coverage to it — two agents appending to one 200-line file is the
 *  collision the path guard exists to prevent. Grouping the fetch cargo tests by feature removes it
 *  permanently, and reads better than one grab-bag either way.
 *
 *  `assume(cargo)`-gated like its sibling, and it needs no staged `ssc` toolchain: the emitter comes
 *  from this build, so these run even when the staged launcher is stale. */
final class TuiFetchCargoTest extends AnyFunSuite:

  private def cargoAvailable: Boolean =
    try Seq("cargo", "--version").!(ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Throwable => false

  /** Emit `view`'s crate with `probe` appended to `main.rs`, run `cargo test <name>`, assert green. */
  private def runProbe(view: View[?], probe: String, testName: String, prefix: String): Unit =
    val module = FrontendModule(List(ComponentDef("App", Nil, _ => view)), "App", "/", targetPlatform = Platform.Terminal)
    val app = new TuiFrameworkBackend().emitNative(module, Platform.Terminal).getOrElse(fail("emitNative returned None"))
    val dir = Files.createTempDirectory(prefix)
    try
      app.sources.foreach { case (rel, content) =>
        val p = dir.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.writeString(p, if rel == "src/main.rs" then content + probe else content)
      }
      val out = new StringBuilder
      val err = new StringBuilder
      val log = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
      val code = Process(Seq("cargo", "test", "--quiet", testName, "--", "--test-threads=1"), dir.toFile).!(log)
      assert(code == 0, s"cargo probe '$testName' failed (exit $code):\n${out.toString}\n${err.toString}")
    finally
      deleteRecursively(dir)

  test("a signal URL retargets the GET, with the tick untouched"):
    assume(cargoAvailable, "cargo not on PATH — skipping fetch cargo gate")
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    def serve(path: String, body: String): Unit =
      server.createContext(path, (ex: com.sun.net.httpserver.HttpExchange) => {
        val bytes = body.getBytes("UTF-8")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      })
    serve("/a", "BODY-FROM-A")
    serve("/b", "BODY-FROM-B")
    server.start()
    try
      val port = server.getAddress.getPort
      val url  = new ReactiveSignal[String]("urlSig", s"http://127.0.0.1:$port/a")
      val feed = new FetchUrlSignal("feed", "", "tick", None, Some(url.id))
      val view = View.Column(Seq(View.SignalText(feed), View.SignalText(url)))
      val probe =
        s"""
           |#[cfg(test)]
           |mod url_signal_regression {
           |    use super::*;
           |
           |    #[test]
           |    fn signal_url_retargets_the_get() {
           |        let mut signals = initial_signals();
           |        bootstrap(&mut signals);
           |        let mut observed = initial_fetch_ticks(&signals);
           |        assert_eq!(sig(&signals, "feed"), "BODY-FROM-A", "bootstrap read the wrong endpoint");
           |
           |        // An unchanged URL and an unchanged tick must NOT refetch.
           |        refresh_fetches(&mut signals, &mut observed);
           |        assert_eq!(sig(&signals, "feed"), "BODY-FROM-A", "idle refresh refetched");
           |
           |        // THE POINT: retarget with the tick untouched.
           |        let before_tick = sig_int(&signals, "tick");
           |        signals.insert("urlSig".to_string(), Value::S("http://127.0.0.1:$port/b".to_string()));
           |        refresh_fetches(&mut signals, &mut observed);
           |        assert_eq!(sig_int(&signals, "tick"), before_tick, "the tick moved — not a URL-driven refetch");
           |        assert_eq!(sig(&signals, "feed"), "BODY-FROM-B", "URL change did not retarget the GET");
           |
           |        // An empty URL makes NO request and keeps the last good body.
           |        signals.insert("urlSig".to_string(), Value::S(String::new()));
           |        refresh_fetches(&mut signals, &mut observed);
           |        assert_eq!(sig(&signals, "feed"), "BODY-FROM-B", "an empty URL blanked the last good value");
           |
           |        // ...and going back re-reads it.
           |        signals.insert("urlSig".to_string(), Value::S("http://127.0.0.1:$port/a".to_string()));
           |        refresh_fetches(&mut signals, &mut observed);
           |        assert_eq!(sig(&signals, "feed"), "BODY-FROM-A", "re-selecting the first endpoint did not refetch");
           |    }
           |}
           |""".stripMargin
      runProbe(view, probe, "signal_url_retargets_the_get", "ssc-tui-urlsig-")
    finally
      server.stop(0)

  test("a fetchAction posts the body, bumps the tick, and clears only on success"):
    assume(cargoAvailable, "cargo not on PATH — skipping fetch cargo gate")
    val posted = new java.util.concurrent.atomic.AtomicReference[String]("")
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/messages", (ex: com.sun.net.httpserver.HttpExchange) => {
      val body =
        if ex.getRequestMethod == "POST" then
          val sent = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
          posted.set(sent)
          "ok"
        else { val v = posted.get(); if v.isEmpty then "NOTHING-POSTED-YET" else v }
      val bytes = body.getBytes("UTF-8")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    // A endpoint that always refuses — the failure half.
    server.createContext("/refuses", (ex: com.sun.net.httpserver.HttpExchange) => {
      val bytes = "nope".getBytes("UTF-8")
      ex.sendResponseHeaders(500, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.start()
    try
      val port = server.getAddress.getPort
      val tick = new ReactiveSignal[Int]("tick", 0)
      val feed = new FetchUrlSignal("feed", s"http://127.0.0.1:$port/messages", tick.id)
      val draft = new ReactiveSignal[String]("draft", "hello-from-the-composer")
      val view = View.Column(Seq(
        View.SignalText(feed),
        View.Button(View.Text(() => "send"),
          EventHandler.FetchAction("POST", s"http://127.0.0.1:$port/messages", draft, tick, clearBody = true)),
        View.Button(View.Text(() => "send-to-refusing"),
          EventHandler.FetchAction("POST", s"http://127.0.0.1:$port/refuses", draft, tick, clearBody = true))
      ))
      val probe =
        """
          |#[cfg(test)]
          |mod fetch_action_regression {
          |    use super::*;
          |
          |    #[test]
          |    fn post_sends_bumps_and_clears() {
          |        let mut signals = initial_signals();
          |        bootstrap(&mut signals);
          |        let mut observed = initial_fetch_ticks(&signals);
          |        assert_eq!(sig(&signals, "feed"), "NOTHING-POSTED-YET", "bootstrap GET did not run");
          |
          |        // Focus 0 is the composer's send button.
          |        activate(0, &mut signals);
          |        assert_eq!(sig_int(&signals, "tick"), 1, "a successful POST must bump the tick");
          |        assert_eq!(sig(&signals, "draft"), "", "clearBody must empty the composer on success");
          |
          |        // The tick is a fetch trigger, so the bound GET re-reads what was just written —
          |        // this is 'post, then watch the list update' with no extra wiring.
          |        refresh_fetches(&mut signals, &mut observed);
          |        assert_eq!(sig(&signals, "feed"), "hello-from-the-composer", "the GET did not see the POST");
          |
          |        // The failure half: a refused POST leaves BOTH the tick and the body alone.
          |        signals.insert("draft".to_string(), Value::S("precious".to_string()));
          |        let tick_before = sig_int(&signals, "tick");
          |        activate(1, &mut signals);
          |        assert_eq!(sig_int(&signals, "tick"), tick_before, "a failed POST bumped the tick");
          |        assert_eq!(sig(&signals, "draft"), "precious", "a failed POST ate the user's message");
          |    }
          |}
          |""".stripMargin
      runProbe(view, probe, "post_sends_bumps_and_clears", "ssc-tui-post-")
      assert(posted.get() == "hello-from-the-composer", s"server saw an unexpected body: '${posted.get()}'")
    finally
      server.stop(0)

  test("choosing a table row retargets the dependent fetch"):
    assume(cargoAvailable, "cargo not on PATH — skipping fetch cargo gate")
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    def serve(path: String, body: => String): Unit =
      server.createContext(path, (ex: com.sun.net.httpserver.HttpExchange) => {
        val bytes = body.getBytes("UTF-8")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
      })
    server.start()
    try
      val port = server.getAddress.getPort
      // The room list ships a READY-MADE url per row: the client never composes a string, which is
      // the same call as shipping a rendered badge. The picker's field is NOT a displayed column,
      // which is exactly why the write re-reads the JSON instead of the parsed display rows.
      serve("/rooms", s"""{"rooms":[
                         |  {"name":"alpha","url":"http://127.0.0.1:$port/t/alpha"},
                         |  {"name":"beta","url":"http://127.0.0.1:$port/t/beta"}
                         |]}""".stripMargin)
      serve("/t/alpha", "TRANSCRIPT-ALPHA")
      serve("/t/beta", "TRANSCRIPT-BETA")

      val urlSig = new ReactiveSignal[String]("urlSig", s"http://127.0.0.1:$port/t/alpha")
      val rooms  = new FetchUrlSignal("rooms", s"http://127.0.0.1:$port/rooms", "tick")
      val feed   = new FetchUrlSignal("feed", "", "tick", None, Some(urlSig.id))
      val view = View.Column(Seq(
        View.SignalText(feed),
        View.DataTable(
          TableDataSource.Remote(rooms, "rooms"),
          List(FieldColumnDef("Room", "name")),
          actions = List(RowActionDef.RowLink("open", urlSig, "url")),
          rowKeyPath = "name"
        )
      ))
      val probe =
        """
          |#[cfg(test)]
          |mod table_selection_regression {
          |    use super::*;
          |
          |    #[test]
          |    fn choosing_a_row_retargets_the_fetch() {
          |        let mut signals = initial_signals();
          |        bootstrap(&mut signals);
          |        let mut observed = initial_fetch_ticks(&signals);
          |        assert_eq!(sig(&signals, "feed"), "TRANSCRIPT-ALPHA", "bootstrap read the wrong room");
          |        assert_eq!(row_count(&sig(&signals, "rooms"), "rooms"), 2, "the room list did not load");
          |
          |        // The table is focus 0. Move the cursor to the second room and choose it.
          |        move_row(0, &mut signals, 1);
          |        assert_eq!(sig_int(&signals, "rooms__row"), 1, "the cursor did not move");
          |        activate(0, &mut signals);
          |
          |        // The bound signal now holds the row's url — a field that is NOT a column.
          |        assert!(sig(&signals, "urlSig").ends_with("/t/beta"), "picked: {}", sig(&signals, "urlSig"));
          |
          |        // ...and THAT is the requirement: the dependent fetch retargets. Asserting the
          |        // signal alone would pass while the picker still did nothing visible.
          |        refresh_fetches(&mut signals, &mut observed);
          |        assert_eq!(sig(&signals, "feed"), "TRANSCRIPT-BETA", "the transcript did not follow the pick");
          |
          |        // The cursor clamps at the end instead of running off it.
          |        move_row(0, &mut signals, 5);
          |        assert_eq!(sig_int(&signals, "rooms__row"), 1, "the cursor ran past the last row");
          |        move_row(0, &mut signals, -5);
          |        assert_eq!(sig_int(&signals, "rooms__row"), 0, "the cursor ran before the first row");
          |
          |        // Choosing over an EMPTY list leaves the selection alone rather than blanking it.
          |        signals.insert("rooms".to_string(), Value::S("{\"rooms\":[]}".to_string()));
          |        let before = sig(&signals, "urlSig");
          |        activate(0, &mut signals);
          |        assert_eq!(sig(&signals, "urlSig"), before, "an empty list blanked the selection");
          |    }
          |}
          |""".stripMargin
      runProbe(view, probe, "choosing_a_row_retargets_the_fetch", "ssc-tui-pick-")
    finally
      server.stop(0)

  test("a remote table inside a column actually renders its rows"):
    assume(cargoAvailable, "cargo not on PATH — skipping fetch cargo gate")
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rooms", (ex: com.sun.net.httpserver.HttpExchange) => {
      val body = """{"entries":[{"name":"ROOM-ALPHA"},{"name":"ROOM-BETA"}]}"""
      val bytes = body.getBytes("UTF-8")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.start()
    try
      val port = server.getAddress.getPort
      val rooms = new FetchUrlSignal("rooms", s"http://127.0.0.1:$port/rooms", "tick")
      // The shape that failed: a table BETWEEN fixed-height siblings in a column.
      val view = View.Column(Seq(
        View.Text(() => "TITLE"),
        View.DataTable(TableDataSource.Remote(rooms, "entries"),
          List(FieldColumnDef("Room", "name")), rowKeyPath = "name"),
        View.Text(() => "FOOTER")
      ))
      val module = FrontendModule(List(ComponentDef("App", Nil, _ => view)), "App", "/", targetPlatform = Platform.Terminal)
      val app = new TuiFrameworkBackend().emitNative(module, Platform.Terminal).getOrElse(fail("emitNative returned None"))
      val dir = Files.createTempDirectory("ssc-tui-tableheight-")
      try
        app.sources.foreach { case (rel, content) =>
          val p = dir.resolve(rel)
          Files.createDirectories(p.getParent)
          Files.writeString(p, content)
        }
        val out = new StringBuilder
        val log = ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n'))
        val code = Process(Seq("cargo", "run", "--quiet"), dir.toFile, "SSC_TUI_SNAPSHOT" -> "1").!(log)
        assert(code == 0, s"cargo run failed (exit $code):\n$out")
        val snapshot = out.toString
        // The emitter test proves the constraint changed; only this proves the ROWS became visible,
        // which is the actual complaint. Both siblings must survive too — the table taking the
        // remaining space must not push them off.
        assert(snapshot.contains("TITLE"), s"the heading was lost:\n$snapshot")
        assert(snapshot.contains("FOOTER"), s"the footer was pushed out:\n$snapshot")
        assert(snapshot.contains("ROOM-ALPHA"), s"first row still invisible:\n$snapshot")
        assert(snapshot.contains("ROOM-BETA"), s"second row still invisible:\n$snapshot")
      finally
        deleteRecursively(dir)
    finally
      server.stop(0)

  private def deleteRecursively(p: Path): Unit =
    try
      if Files.exists(p) then
        Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(f => Files.deleteIfExists(f))
    catch case _: Throwable => ()
