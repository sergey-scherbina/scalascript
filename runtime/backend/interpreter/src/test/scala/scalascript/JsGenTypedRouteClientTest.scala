package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

class JsGenTypedRouteClientTest extends AnyFunSuite:

  private val source =
    """---
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

  test("JS codegen emits HTTP typed route client metadata and Promise methods"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("const _ssc_typedRouteClients = ["))
    assert(code.contains("""{client: "Messages", name: "create", method: "POST", path: "/api/messages", requestType: "CreateMessage", responseType: "Message"}"""))
    assert(code.contains("async function _ssc_api_request(methodRaw, pathTemplate, input)"))
    assert(code.contains("const response = await fetch(url, init);"))
    assert(code.contains("const Messages = {"))
    assert(code.contains("""create(input) { return _ssc_api_request("POST", "/api/messages", input); }"""))
    assert(code.contains("""list() { return _ssc_api_request("GET", "/api/messages", undefined); }"""))
    assert(code.contains("""get(input) { return _ssc_api_request("GET", "/api/messages/:id", input); }"""))

  test("JS typed route runtime builds path params and GET query strings"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("function _ssc_api_path(pathTemplate, input)"))
    assert(code.contains("const primitiveForSingleParam ="))
    assert(code.contains("throw new Error(\"typed route client: missing path field '\" + name + \"'\");"))
    assert(code.contains("function _ssc_api_query(pathTemplate, input)"))
    assert(code.contains("const url = _ssc_api_path(pathTemplate, input) + (method === \"GET\" ? _ssc_api_query(pathTemplate, input) : \"\");"))

  test("segmented JS output includes HTTP typed route clients before user code"):
    val segments = JsGen.generateSegmented(Parser.parse(source))
    val js = segments.collect { case JsGen.Segment.ScalaScriptJs(code) => code }.mkString("\n")

    assert(js.contains("const _ssc_typedRouteClients = ["))
    assert(js.contains("const Messages = {"))
    assert(js.indexOf("const Messages = {") < js.indexOf("function CreateMessage("))
