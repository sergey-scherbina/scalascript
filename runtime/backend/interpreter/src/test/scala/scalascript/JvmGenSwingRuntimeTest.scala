package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.file.Files
import java.nio.file.Path

class JvmGenSwingRuntimeTest extends AnyFunSuite:

  test("Swing frontend helper launches same-process runtime instead of nested scala-cli"):
    val src =
      """
        |---
        |frontend: swing
        |---
        |
        |```scalascript
        |val view = text("Hello")
        |serve(view, 0)
        |```
        |""".stripMargin
    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("scalascript.frontend.swing.SwingRuntime.run("))
    assert(code.contains("mode:   same-process JVM"))
    assert(code.contains("_ssc_ui_inprocess_fetch(method, url, body)"))
    assert(code.contains("new scalascript.frontend.swing.SwingRuntime.FetchDispatcher"))
    assert(!code.contains("ProcessBuilder(_scalaCli"))

  test("Swing full-stack example generates same-process fetch dispatcher"):
    val root = repoRoot
    val src = Files.readString(root.resolve("examples/frontend/swing-fullstack/swing-fullstack.ssc"))
    val code = JvmGen.generate(
      Parser.parse(src),
      baseDir = Some(os.Path(root.resolve("runtime").toFile)),
      frontendOverride = Some("swing")
    )

    assert(code.contains("""route("POST", "/api/messages")"""))
    assert(code.contains("fetchActionClear(\"POST\", \"/api/messages\""))
    assert(code.contains("_ssc_ui_inprocess_fetch(method, url, body)"))
    assert(code.contains("new scalascript.frontend.swing.SwingRuntime.FetchDispatcher"))

  private def repoRoot: Path =
    Iterator.iterate(Path.of(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.toAbsolutePath)
      .find(path => Files.exists(path.resolve("runtime/std/ui/primitives.ssc")))
      .getOrElse(fail("missing repo root"))
