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
