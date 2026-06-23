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
    // heading + a reactive signal-bound line, a divider, then a two-column row.
    val msg = new ReactiveSignal[String]("msg", "first")
    val view = View.Column(Seq(
      View.Text(() => "Title"),
      View.SignalText(msg),
      View.Divider(),
      View.Row(Seq(View.Text(() => "left"), View.Text(() => "right")))
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

  private def deleteRecursively(p: Path): Unit =
    try
      if Files.exists(p) then
        scala.util.Using.resource(Files.walk(p)) { s =>
          import scala.jdk.CollectionConverters.*
          s.iterator().asScala.toList.reverse.foreach(f =>
            try Files.deleteIfExists(f) catch case _: Throwable => ())
        }
    catch case _: Throwable => ()
