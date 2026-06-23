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
    // A small static layout: heading + text, then a divider, then a two-column row.
    val view = View.Column(Seq(
      View.Text(() => "Title"),
      View.Text(() => "hello"),
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
      val code = Process(Seq("cargo", "run", "--quiet"), dir.toFile).!(logger)
      assert(code == 0, s"cargo run failed (exit $code) — emitted Rust did not compile:\n${err.toString}")
      val rendered = out.toString
      // The buffer snapshot must contain the laid-out text content.
      assert(rendered.contains("Title"),  s"missing heading:\n$rendered")
      assert(rendered.contains("hello"),  s"missing text:\n$rendered")
      assert(rendered.contains("left"),   s"missing row-left:\n$rendered")
      assert(rendered.contains("right"),  s"missing row-right:\n$rendered")
      assert(rendered.contains("─"),      s"missing divider rule:\n$rendered")
      // Row children render side-by-side on the same line.
      val rowLine = rendered.linesIterator.find(l => l.contains("left") && l.contains("right"))
      assert(rowLine.isDefined, s"row children not on the same line:\n$rendered")
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
