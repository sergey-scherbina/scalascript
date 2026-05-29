package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.file.Files
import java.nio.file.Path

@org.scalatest.Ignore
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
    assert(code.contains("scalascript-backend-spi"))
    assert(code.contains("private val _ssc_ui_backend_transport: scalascript.backend.spi.BackendTransport"))
    assert(code.contains("scalascript.backend.spi.BackendRequest("))
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
    assert(code.contains("""route("GET", "/api/messages")"""))
    assert(code.contains("""route("POST", "/api/messages/delete")"""))
    assert(code.contains("scalascript-backend-spi"))
    assert(code.contains("fetchActionClear(\"POST\", \"/api/messages\""))
    assert(code.contains("fetchTable(\"/api/messages\", \"/api/messages/delete\""))
    assert(code.contains("private val _ssc_ui_backend_transport: scalascript.backend.spi.BackendTransport"))
    assert(code.contains("_ssc_ui_backend_request(methodRaw, url, body)"))
    assert(code.contains("_ssc_ui_inprocess_fetch(method, url, body)"))
    assert(code.contains("new scalascript.frontend.swing.SwingRuntime.FetchDispatcher"))

  test("Swing typed-client example generates callable client over same-process dispatcher"):
    val root = repoRoot
    val src = Files.readString(root.resolve("examples/frontend/swing-typed-client/swing-typed-client.ssc"))
    val code = JvmGen.generate(
      Parser.parse(src),
      baseDir = Some(os.Path(root.resolve("runtime").toFile)),
      frontendOverride = Some("swing")
    )

    assert(code.contains("object Messages:"))
    assert(code.contains("""def create(input: CreateMessage): Message = _ssc_api_request[CreateMessage, Message]("POST", "/api/messages", input)"""))
    assert(code.contains("""def list(): List[Message] = _ssc_api_request[Unit, List[Message]]("GET", "/api/messages", ())"""))
    assert(code.contains("""def delete(input: Int): Unit = _ssc_api_request[Int, Unit]("POST", "/api/messages/delete", input)"""))
    assert(code.contains("_ssc_ui_backend_request(method, url, _ssc_api_body[Req](method, input))"))
    assert(code.contains("val created = Messages.create(CreateMessage("))

  test("Swing frontend emits iconPath in generated Options when app-icon front matter is set"):
    val src =
      """|---
         |frontend: swing
         |app-icon: /usr/share/icons/myapp.png
         |---
         |
         |```scalascript
         |val view = text("Hello")
         |serve(view, 0)
         |```
         |""".stripMargin
    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("""iconPath = Some("/usr/share/icons/myapp.png")"""))

  test("Swing frontend does not emit iconPath when app-icon is absent"):
    val src =
      """|---
         |frontend: swing
         |---
         |
         |```scalascript
         |val view = text("Hello")
         |serve(view, 0)
         |```
         |""".stripMargin
    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(!code.contains("iconPath"))

  private def repoRoot: Path =
    Iterator.iterate(Path.of(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.toAbsolutePath)
      .find(path => Files.exists(path.resolve("runtime/std/ui/primitives.ssc")))
      .getOrElse(fail("missing repo root"))
