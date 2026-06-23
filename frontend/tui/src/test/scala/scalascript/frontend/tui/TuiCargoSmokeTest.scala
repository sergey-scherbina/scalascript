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
    val module = FrontendModule(
      components     = List(ComponentDef("App", Nil, _ => View.Text(() => "hello"))),
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
      assert(out.toString.contains("ssc-tui: ok"), s"unexpected program output:\n${out.toString}")
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
