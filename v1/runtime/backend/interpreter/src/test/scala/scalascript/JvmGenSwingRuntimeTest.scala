package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

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
    val root = TestPaths.repoRoot
    val src = os.read(root / "examples" / "frontend" / "swing-fullstack" / "swing-fullstack.ssc")
    val code = JvmGen.generate(
      Parser.parse(src),
      baseDir = Some(root / "runtime"),
      frontendOverride = Some("swing")
    )

    assert(code.contains("""route("POST", "/api/messages")"""))
    assert(code.contains("""route("GET", "/api/messages")"""))
    assert(code.contains("""route("POST", "/api/messages/delete")"""))
    assert(code.contains("scalascript-backend-spi"))
    assert(code.contains("fetchActionClear(\"POST\", \"/api/messages\""))
    assert(code.contains("dataTable("))
    assert(code.contains("private val _ssc_ui_backend_transport: scalascript.backend.spi.BackendTransport"))
    assert(code.contains("_ssc_ui_backend_request(methodRaw, url, body)"))
    assert(code.contains("_ssc_ui_inprocess_fetch(method, url, body)"))
    assert(code.contains("new scalascript.frontend.swing.SwingRuntime.FetchDispatcher"))

  test("Swing typed-client example generates callable client over same-process dispatcher"):
    val root = TestPaths.repoRoot
    val src = os.read(root / "examples" / "frontend" / "swing-typed-client" / "swing-typed-client.ssc")
    val code = JvmGen.generate(
      Parser.parse(src),
      baseDir = Some(root / "runtime"),
      frontendOverride = Some("swing")
    )

    assert(code.contains("object Messages:"))
    assert(code.contains("""def create(input: CreateMessage, headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): Message = _ssc_api_request[CreateMessage, Message]("POST", "/api/messages", input, headers, cancelToken)"""))
    assert(code.contains("""def list(headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): List[Message] = _ssc_api_request[Unit, List[Message]]("GET", "/api/messages", (), headers, cancelToken)"""))
    assert(code.contains("""def delete(input: Int, headers: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): Unit = _ssc_api_request[Int, Unit]("POST", "/api/messages/delete", input, headers, cancelToken)"""))
    assert(code.contains("val req = scalascript.backend.spi.BackendRequest(method, url,"))
    assert(code.contains("_ssc_api_send(req,"))
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
