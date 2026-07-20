package scalascript.frontend.tui

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import java.nio.file.{Files, Path}
import scala.sys.process.*

/** End-to-end gate for the `frontend/tui` slice 0 scaffold: the crate the
 *  backend emits actually `cargo build`s + runs (catches API drift that a
 *  string-match test can't). `assume(cargo)`-gated so a CI box without
 *  `cargo` skips cleanly — mirrors `RustGenCargoSmokeTest`. The first run
 *  fetches the ratatui crate (network) and is slow. */
final class TuiCargoSmokeTest extends AnyFunSuite:

  private def cargoAvailable: Boolean =
    try Seq("cargo", "--version").!(ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Throwable => false

  test("emitted ratatui crate compiles and runs via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping ratatui smoke")
    // heading + a reactive signal-bound line, a divider, a two-column row,
    // and interactive widgets (button increments a signal, a text input).
    val msg   = new ReactiveSignal[String]("msg", "first")
    val count = new ReactiveSignal[Int]("count", 0)
    val name  = new ReactiveSignal[String]("name", "")
    val view = View.Column(Seq(
      View.Text(() => "Title"),
      View.SignalText(msg),
      View.Divider(),
      View.Row(Seq(View.Text(() => "left"), View.Text(() => "right"))),
      View.Button(View.Text(() => "inc"), EventHandler.IncrementSignal(count, 1)),
      View.TextInput(name, "type here")
    ))
    val module = FrontendModule(
      components     = List(ComponentDef("App", Nil, _ => view)),
      entryPoint     = "App",
      initialRoute   = "/",
      targetPlatform = Platform.Terminal
    )
    val app = new TuiFrameworkBackend().emitNative(module, Platform.Terminal).getOrElse(
      fail("emitNative returned None for Platform.Terminal")
    )

    val dir = Files.createTempDirectory("ssc-tui-smoke-")
    try
      app.sources.foreach { case (rel, content) =>
        val p = dir.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.writeString(p, content)
      }
      val out = new StringBuilder
      val err = new StringBuilder
      val logger = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
      // Headless snapshot — SSC_TUI_SNAPSHOT bypasses the interactive crossterm
      // loop (which would need a TTY), rendering one frame to stdout.
      val code = Process(Seq("cargo", "run", "--quiet"), dir.toFile, "SSC_TUI_SNAPSHOT" -> "1").!(logger)
      assert(code == 0, s"cargo run failed (exit $code) — emitted Rust did not compile:\n${err.toString}")
      val rendered = out.toString
      // The buffer snapshot must contain the laid-out content (signal value included).
      assert(rendered.contains("Title"),  s"missing heading:\n$rendered")
      assert(rendered.contains("first"),  s"missing signal value:\n$rendered")
      assert(rendered.contains("left"),   s"missing row-left:\n$rendered")
      assert(rendered.contains("right"),  s"missing row-right:\n$rendered")
      assert(rendered.contains("─"),      s"missing divider rule:\n$rendered")
      // Row children render side-by-side on the same line.
      val rowLine = rendered.linesIterator.find(l => l.contains("left") && l.contains("right"))
      assert(rowLine.isDefined, s"row children not on the same line:\n$rendered")

      // Reactivity gate: the generated #[cfg(test)] reactive_rerender proves a
      // signal mutation re-renders.
      val testOut = new StringBuilder
      val testErr = new StringBuilder
      val testLog = ProcessLogger(l => testOut.append(l).append('\n'), l => testErr.append(l).append('\n'))
      val testCode = Process(Seq("cargo", "test", "--quiet"), dir.toFile).!(testLog)
      assert(testCode == 0, s"cargo test (reactive_rerender) failed:\n${testOut.toString}\n${testErr.toString}")
    finally
      deleteRecursively(dir)

  test("DataTable + TabBar render via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping ratatui smoke")
    val tab = new ReactiveSignal[Int]("tab", 0)
    val dt = View.DataTable(
      TableDataSource.StaticRows(List(
        Map("room" -> "demo", "unread" -> "2"),
        Map("room" -> "rozum", "unread" -> "5")
      )),
      List(FieldColumnDef("Room", "room"), FieldColumnDef("Unread", "unread")),
      rowKeyPath = "room"
    )
    val view = View.TabBar(
      Seq(Tab("Rooms", None, dt), Tab("Models", None, View.Text(() => "models panel"))),
      tab
    )
    val out = snapshotViaCargo(view)
    assert(out.contains("[Rooms]"),                       s"active tab header missing:\n$out")
    assert(out.contains("Room") && out.contains("Unread"), s"table header missing:\n$out")
    assert(out.contains("demo") && out.contains("rozum"), s"table rows missing:\n$out")

  test("fetch-bound signal renders the fetched body via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping ratatui smoke")
    // A local HTTP server returning a known payload; the emitted crate fetches it
    // at bootstrap and a SignalText renders it.
    val payload = "ROOMS_FETCHED_OK"
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rooms", (ex: com.sun.net.httpserver.HttpExchange) => {
      val bytes = payload.getBytes("UTF-8")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.start()
    try
      val port = server.getAddress.getPort
      val feed = new FetchUrlSignal("feed", s"http://127.0.0.1:$port/rooms", "tick")
      val view = View.Column(Seq(View.Text(() => "Rooms:"), View.SignalText(feed)))
      val out = snapshotViaCargo(view)
      assert(out.contains(payload), s"fetched body not rendered:\n$out")
    finally
      server.stop(0)

  test("DataTable.Remote fetches JSON and renders the rows via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping ratatui smoke")
    val json = """{"data":[{"room":"demo","unread":2},{"room":"rozum","unread":5}]}"""
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rooms", (ex: com.sun.net.httpserver.HttpExchange) => {
      val bytes = json.getBytes("UTF-8")
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.start()
    try
      val port = server.getAddress.getPort
      val feed = new FetchUrlSignal("rooms", s"http://127.0.0.1:$port/rooms", "tick")
      val view = View.DataTable(
        TableDataSource.Remote(feed, "data"),
        List(FieldColumnDef("Room", "room"), FieldColumnDef("Unread", "unread")),
        rowKeyPath = "room"
      )
      val out = snapshotViaCargo(view, runTests = true)
      assert(out.contains("Room") && out.contains("Unread"), s"table header missing:\n$out")
      assert(out.contains("demo") && out.contains("rozum"), s"fetched rows missing:\n$out")
      assert(out.contains("2") && out.contains("5"),        s"fetched cell values missing:\n$out")
    finally
      server.stop(0)

  test("refresh tick refetches a remote table via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping ratatui smoke")
    val requests = new java.util.concurrent.atomic.AtomicInteger(0)
    val initialJson = """[{"ts":1,"author":"first-agent","content":"initial-message"}]"""
    val refreshedJson = """[{"ts":2,"author":"second-agent","content":"refreshed-message"}]"""
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/messages", (ex: com.sun.net.httpserver.HttpExchange) => {
      val request = requests.getAndIncrement()
      val (status, body) = request match
        case 0 => (200, initialJson)
        case 1 => (200, refreshedJson)
        case _ => (500, "refresh-failed")
      val bytes = body.getBytes("UTF-8")
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.start()
    try
      val port = server.getAddress.getPort
      val tick = new ReactiveSignal[Int]("refreshTick", 0)
      val feed = new FetchUrlSignal("messages", s"http://127.0.0.1:$port/messages", tick.id)
      val view = View.Column(Seq(
        View.DataTable(
          TableDataSource.Remote(feed),
          List(FieldColumnDef("Author", "author"), FieldColumnDef("Message", "content")),
          rowKeyPath = "ts"
        ),
        View.Button(View.Text(() => "refresh"), EventHandler.IncrementSignal(tick))
      ))
      val module = FrontendModule(List(ComponentDef("App", Nil, _ => view)), "App", "/", targetPlatform = Platform.Terminal)
      val app = new TuiFrameworkBackend().emitNative(module, Platform.Terminal).getOrElse(fail("emitNative returned None"))
      val probe =
        """
          |#[cfg(test)]
          |mod refresh_tick_regression {
          |    use super::*;
          |
          |    #[test]
          |    fn refresh_tick_refetches() {
          |        let mut signals = initial_signals();
          |        bootstrap(&mut signals);
          |        let mut observed = initial_fetch_ticks(&signals);
          |        let before = sig(&signals, "messages");
          |        assert!(before.contains("initial-message"), "unexpected bootstrap body: {}", before);
          |        refresh_fetches(&mut signals, &mut observed);
          |        assert_eq!(sig(&signals, "messages"), before, "unchanged tick refetched");
          |        activate(0, &mut signals);
          |        assert_eq!(sig_int(&signals, "refreshTick"), 1);
          |        refresh_fetches(&mut signals, &mut observed);
          |        let after = sig(&signals, "messages");
          |        assert!(after.contains("refreshed-message"), "refresh body was not installed: {}", after);
          |        assert!(!after.contains("initial-message"), "stale body survived refresh: {}", after);
          |        refresh_fetches(&mut signals, &mut observed);
          |        assert_eq!(sig(&signals, "messages"), after, "stable tick refetched");
          |        activate(0, &mut signals);
          |        refresh_fetches(&mut signals, &mut observed);
          |        assert_eq!(sig(&signals, "messages"), after, "failed refresh discarded last-good body");
          |    }
          |}
          |""".stripMargin
      val dir = Files.createTempDirectory("ssc-tui-refresh-")
      try
        app.sources.foreach { case (rel, content) =>
          val p = dir.resolve(rel)
          Files.createDirectories(p.getParent)
          Files.writeString(p, if rel == "src/main.rs" then content + probe else content)
        }
        val out = new StringBuilder
        val err = new StringBuilder
        val log = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
        val code = Process(Seq("cargo", "test", "--quiet", "refresh_tick_refetches", "--", "--test-threads=1"), dir.toFile).!(log)
        assert(code == 0, s"cargo refresh test failed (exit $code):\n${out.toString}\n${err.toString}")
        assert(requests.get() == 3, s"expected bootstrap + success + failed refresh GET, saw ${requests.get()}")
      finally
        deleteRecursively(dir)
    finally
      server.stop(0)

  /** Emit `view`'s crate, `cargo run` it in `SSC_TUI_SNAPSHOT` mode (no TTY),
   *  return stdout. Cleans up the temp crate. */
  private def snapshotViaCargo(view: View[?], runTests: Boolean = false): String =
    val module = FrontendModule(List(ComponentDef("App", Nil, _ => view)), "App", "/", targetPlatform = Platform.Terminal)
    val app = new TuiFrameworkBackend().emitNative(module, Platform.Terminal).getOrElse(fail("emitNative returned None"))
    val dir = Files.createTempDirectory("ssc-tui-smoke2-")
    try
      app.sources.foreach { case (rel, content) =>
        val p = dir.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.writeString(p, content)
      }
      val out = new StringBuilder
      val err = new StringBuilder
      val log = ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
      val code = Process(Seq("cargo", "run", "--quiet"), dir.toFile, "SSC_TUI_SNAPSHOT" -> "1").!(log)
      assert(code == 0, s"cargo run failed (exit $code):\n${err.toString}")
      if runTests then
        val testCode = Process(Seq("cargo", "test", "--quiet"), dir.toFile).!(log)
        assert(testCode == 0, s"cargo test failed (exit $testCode):\n${err.toString}")
      out.toString
    finally
      deleteRecursively(dir)

  private def deleteRecursively(p: Path): Unit =
    try
      if Files.exists(p) then
        scala.util.Using.resource(Files.walk(p)) { s =>
          import scala.jdk.CollectionConverters.*
          s.iterator().asScala.toList.reverse.foreach(f =>
            try Files.deleteIfExists(f) catch case _: Throwable => ())
        }
    catch case _: Throwable => ()
