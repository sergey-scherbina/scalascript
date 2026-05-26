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
    assert(code.contains("_ssc_ui_backend_transport.request("))
    assert(code.contains("_ssc_typed_json_decode_response[Resp](response)"))
    assert(code.contains("object Messages:"))
    assert(code.contains("""def create(input: CreateMessage, headers: Map[String, String] = Map.empty): Message = _ssc_api_request[CreateMessage, Message]("POST", "/api/messages", input, headers)"""))
    assert(code.contains("""def list(headers: Map[String, String] = Map.empty): List[Message] = _ssc_api_request[Unit, List[Message]]("GET", "/api/messages", (), headers)"""))
    assert(code.contains("""def delete(input: Int, headers: Map[String, String] = Map.empty): Unit = _ssc_api_request[Int, Unit]("POST", "/api/messages/delete", input, headers)"""))
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

  test("JVM codegen emits auth header helpers in Swing typed client runtime") {
    val src =
      """---
        |frontend: swing
        |apiClients:
        |  Messages:
        |    endpoints:
        |      - name: list
        |        method: GET
        |        path: /api/messages
        |        request: Unit
        |        response: String
        |---
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("private var _ssc_api_extra_headers: Map[String, String] = Map.empty"))
    assert(code.contains("def _ssc_api_set_headers(headers: Map[String, String]): Unit"))
    assert(code.contains("def _ssc_set_auth_token(token: String): Unit"))
    assert(code.contains("baseHeaders ++ _ssc_api_extra_headers ++ callHeaders"))
    assert(code.contains("private var _ssc_api_retry_policy: (Int, Long) = (0, 0L)"))
    assert(code.contains("def _ssc_api_set_retry(maxRetries: Int, delayMs: Long): Unit"))
    assert(code.contains("private def _ssc_api_send("))
    assert(code.contains("_ssc_ui_backend_transport.request(req)"))
  }

  test("JVM codegen path param validation: Unit request type emits warning comment") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: get
        |        method: GET
        |        path: /api/items/:id
        |        request: Unit
        |        response: String
        |---
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: path param ':id' cannot be filled — request type is Unit"))
  }

  test("JVM codegen path param validation: case class with matching field produces no warning") {
    val src =
      """---
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: get
        |        method: GET
        |        path: /api/items/:id
        |        request: ItemQuery
        |        response: String
        |---
        |
        |# Test
        |
        |```scalascript
        |case class ItemQuery(id: Int)
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src))
    assert(!code.contains("// [ssc warning]"))
  }

  test("JVM codegen emits per-call headers param in client methods and runtime") {
    val src =
      """---
        |frontend: swing
        |apiClients:
        |  Items:
        |    endpoints:
        |      - name: get
        |        method: GET
        |        path: /api/items/:id
        |        request: Int
        |        response: String
        |      - name: create
        |        method: POST
        |        path: /api/items
        |        request: String
        |        response: Int
        |---
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(src), frontendOverride = Some("swing"))

    assert(code.contains("callHeaders: Map[String, String] = Map.empty"))
    assert(code.contains("baseHeaders ++ _ssc_api_extra_headers ++ callHeaders"))
    assert(code.contains("""def get(input: Int, headers: Map[String, String] = Map.empty): String"""))
    assert(code.contains("""def create(input: String, headers: Map[String, String] = Map.empty): Int"""))
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
