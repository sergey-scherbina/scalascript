package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class JvmGenTypedRouteClientTest extends AnyFunSuite:

  test("JVM codegen preserves typed route client endpoint metadata") {
    val src =
      """---
        |apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: POST
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message
        |      - name: get
        |        method: GET
        |        path: /api/messages/:id
        |        request: Int
        |        response: Message
        |---
        |
        |# Test
        |
        |```scalascript
        |case class CreateMessage(text: String)
        |case class Message(id: Int, text: String)
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src))

    assert(code.contains("final case class _TypedRouteClientEndpoint"))
    assert(code.contains("val _ssc_typedRouteClients: List[_TypedRouteClientEndpoint]"))
    assert(code.contains("""_TypedRouteClientEndpoint("Messages", "create", "POST", "/api/messages", "CreateMessage", "Message")"""))
    assert(code.contains("""_TypedRouteClientEndpoint("Messages", "get", "GET", "/api/messages/:id", "Int", "Message")"""))
  }

  test("Swing JVM codegen emits callable typed client methods over in-process fetch") {
    val src =
      """---
        |frontend: swing
        |apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: POST
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message
        |      - name: list
        |        method: GET
        |        path: /api/messages
        |        request: Unit
        |        response: List[Message]
        |      - name: delete
        |        method: POST
        |        path: /api/messages/delete
        |        request: Int
        |        response: Unit
        |---
        |
        |# Test
        |
        |```scalascript
        |case class CreateMessage(text: String)
        |case class Message(id: Int, text: String)
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("inline def _ssc_api_request[Req, Resp]"))
    assert(code.contains("scalascript-backend-typed-data-runtime"))
    assert(code.contains("private inline def _ssc_typed_json_encode[T](value: T): String"))
    assert(code.contains("summonInline[scalascript.typeddata.JsonCodec[T]].encode(value)"))
    assert(code.contains("summonInline[scalascript.typeddata.JsonCodec[T]].decode"))
    assert(code.contains("private inline def _ssc_typed_json_decode_response[T](response: scalascript.backend.spi.BackendResponse): T"))
    assert(code.contains("else _ssc_typed_json_encode[Req](input)"))
    assert(code.contains("_ssc_ui_backend_request(method, url, _ssc_api_body[Req](method, input))"))
    assert(code.contains("_ssc_typed_json_decode_response[Resp](response)"))
    assert(code.contains("object Messages:"))
    assert(code.contains("""def create(input: CreateMessage): Message = _ssc_api_request[CreateMessage, Message]("POST", "/api/messages", input)"""))
    assert(code.contains("""def list(): List[Message] = _ssc_api_request[Unit, List[Message]]("GET", "/api/messages", ())"""))
    assert(code.contains("""def delete(input: Int): Unit = _ssc_api_request[Int, Unit]("POST", "/api/messages/delete", input)"""))
  }

  test("non-Swing JVM codegen keeps metadata only until HTTP transport lands") {
    val src =
      """---
        |apiClients:
        |  Messages:
        |    endpoints:
        |      - name: create
        |        method: POST
        |        path: /api/messages
        |        request: CreateMessage
        |        response: Message
        |---
        |
        |# Test
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src))

    assert(code.contains("val _ssc_typedRouteClients: List[_TypedRouteClientEndpoint]"))
    assert(!code.contains("object Messages:"))
    assert(!code.contains("inline def _ssc_api_request[Req, Resp]"))
  }

  test("JVM codegen skips client-only ScalaScript blocks") {
    val src =
      """# Test
        |
        |```scalascript @side=client
        |val rows = awaitClient(Messages.list())
        |```
        |
        |```scalascript
        |val serverValue = 1
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src))

    assert(!code.contains("awaitClient"))
    assert(!code.contains("Messages.list"))
    assert(code.contains("val serverValue = 1"))
  }
