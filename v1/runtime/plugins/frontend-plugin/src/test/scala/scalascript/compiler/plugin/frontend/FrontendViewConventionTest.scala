package scalascript.compiler.plugin.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scalascript.testkit.TestInterpreter
import java.nio.file.{Files, Path}

/** The `def view()` convention: when a UI frontend backend is explicitly
 *  selected, running a module that defines a zero-arg top-level `view` (and no
 *  `main`) renders it through the active backend — `serve(...)` for web,
 *  `emit(...)` for native (e.g. the ratatui `tui` crate). One `.ssc` → web OR
 *  terminal with no web-specific `serve(..., port)` in the source. */
final class FrontendViewConventionTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(FrontendInterpreterPlugin()))

  /** A native backend that records whether `emitNative` was invoked. */
  private final class RecordingNativeBackend(val nm: String) extends FrontendFrameworkSpi:
    var count = 0
    def name: String = nm
    def capabilities: Set[Capability] = Set.empty
    def jsDeps: List[JsDep] = Nil
    override def supportedPlatforms: Set[Platform] = Set(Platform.Terminal)
    def emit(m: FrontendModule): EmittedSpa = throw new UnsupportedOperationException("native-only")
    override def emitNative(m: FrontendModule, p: Platform): Option[EmittedArtifact.NativeApp] =
      count += 1
      Some(EmittedArtifact.NativeApp(
        Map("Cargo.toml" -> "x"), Map.empty, "cargo run",
        AppManifest("c", "C", "1.0.0"), AppFormat.RatatuiApp, Platform.Terminal))

  private def rm(dir: String): Unit =
    try
      val p = Path.of(dir)
      if Files.exists(p) then
        scala.util.Using.resource(Files.walk(p)) { s =>
          import scala.jdk.CollectionConverters.*
          s.iterator().asScala.toList.reverse.foreach(f => try Files.deleteIfExists(f) catch case _: Throwable => ())
        }
    catch case _: Throwable => ()

  private def cleanup(): Unit = { rm("tui-out"); rm("guard-out") }

  test("def view() + explicit native frontend → autoRunView dispatches to emitNative once"):
    val backend = RecordingNativeBackend("rec-native-view")
    FrontendFrameworks.register(backend)
    FrontendFrameworks.setBackend("rec-native-view")
    try
      interp.eval("""def view() = textNode("hi")""")
      assert(backend.count == 1, s"expected exactly one auto-render, got ${backend.count}")
    finally
      FrontendFrameworks.setBackend(null)
      cleanup()

  test("def view() with NO explicit frontend selection → does not auto-run"):
    val backend = RecordingNativeBackend("rec-native-noop")
    FrontendFrameworks.register(backend)
    FrontendFrameworks.setBackend(null) // no explicit selection
    try
      interp.eval("""def view() = textNode("hi")""")
      assert(backend.count == 0, "autoRunView fired without an explicit frontend selection")
    finally
      FrontendFrameworks.setBackend(null)
      cleanup()

  test("def view() but module renders explicitly (emit) → no double-fire"):
    val backend = RecordingNativeBackend("rec-native-guard")
    FrontendFrameworks.register(backend)
    FrontendFrameworks.setBackend("rec-native-guard")
    try
      // explicit emit at top level → the convention must skip (even with view() defined)
      interp.eval(
        """def view() = textNode("hi")
          |emit(view(), "guard-out")""".stripMargin)
      assert(backend.count == 1, s"expected only the explicit emit (no auto double-fire), got ${backend.count}")
    finally
      FrontendFrameworks.setBackend(null)
      cleanup()
