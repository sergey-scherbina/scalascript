package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

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
    assert(code.contains("async function _ssc_api_request(methodRaw, pathTemplate, input, requestType, responseType)"))
    assert(code.contains("function _ssc_typed_json_encode(value, typeName)"))
    assert(code.contains("function _ssc_typed_json_decode_response(text, contentType, typeName)"))
    assert(code.contains("return _ssc_typed_json_encode(input, requestType);"))
    assert(code.contains("const response = await fetch(url, init);"))
    assert(code.contains("return _ssc_typed_json_decode_response(text, contentType, responseType);"))
    assert(code.contains("const Messages = {"))
    assert(code.contains("""create(input) { return _ssc_api_request("POST", "/api/messages", input, "CreateMessage", "Message"); }"""))
    assert(code.contains("""list() { return _ssc_api_request("GET", "/api/messages", undefined, "Unit", "List[Message]"); }"""))
    assert(code.contains("""get(input) { return _ssc_api_request("GET", "/api/messages/:id", input, "Int", "Message"); }"""))
    assert(code.contains("""_ssc_typed_json_register_product("CreateMessage", ["text"], CreateMessage)"""))
    assert(code.contains("""_ssc_typed_json_register_product("Message", ["id", "text"], Message)"""))

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

  test("awaitClient lowers typed client Promises through an async wrapper"):
    val src =
      source +
        """
          |
          |```scalascript
          |val rows = awaitClient(Messages.list())
          |println("rows=" + rows.size.toString)
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("""const rows = await _dispatch(Messages, 'list', []);"""))
    assert(code.contains("document.body.textContent = msg"))

  test("segmented output wraps awaitClient and skips server-only ScalaScript blocks"):
    val src =
      source +
        """
          |
          |```scalascript @side=server
          |val serverOnly = awaitClient(Messages.list())
          |```
          |
          |```scalascript @side=client
          |val clientRows = awaitClient(Messages.list())
          |```
          |""".stripMargin

    val segments = JsGen.generateSegmented(Parser.parse(src))
    val js = segments.collect { case JsGen.Segment.ScalaScriptJs(code) => code }.mkString("\n")

    assert(js.contains("(async () => {"))
    assert(js.contains("""const clientRows = await _dispatch(Messages, 'list', []);"""))
    assert(!js.contains("serverOnly"))

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(js: String): String =
    val tmp = java.io.File.createTempFile("ssc-js-typed-client-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  test("def with awaitClient in body emits async function"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |def loadAll() = awaitClient(Messages.list())
          |val rows = awaitClient(loadAll())
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("async function loadAll()"))
    assert(code.contains("const rows = await _call(loadAll,"))

  test("for-yield with multiple awaitClient generators lowers to async IIFE"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |val combined = awaitClient(for {
          |  msgs <- awaitClient(Messages.list())
          |  drafts <- awaitClient(Messages.list())
          |} yield msgs ++ drafts)
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("await _dispatch(Messages, 'list',"))
    assert(!code.contains("'flatMap'"))

  test("for-do with multiple awaitClient generators lowers to async IIFE"):
    val src =
      source +
        """
          |
          |```scalascript @side=client
          |for {
          |  msgs <- awaitClient(Messages.list())
          |  drafts <- awaitClient(Messages.list())
          |} {
          |  println(msgs.size.toString + "+" + drafts.size.toString)
          |}
          |```
          |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))

    assert(code.contains("(async () => {"))
    assert(code.contains("await _dispatch(Messages, 'list',"))
    assert(!code.contains("'forEach'"))

  test("path param validation: Unit request type with path param emits warning comment"):
    val src =
      """|---
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

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: path param ':id' cannot be filled — request type is Unit"))

  test("path param validation: matching case class fields produce no warning"):
    val src =
      """|---
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
         |case class ItemQuery(id: Int, category: String)
         |```
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(!code.contains("// [ssc warning]"))

  test("path param validation: case class missing path param field emits warning"):
    val src =
      """|---
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
         |case class ItemQuery(name: String)
         |```
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: path param ':id' not found in case class 'ItemQuery'"))

  test("path param validation: primitive with single path param produces no warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:id
         |        request: Int
         |        response: String
         |---
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(!code.contains("// [ssc warning]"))

  test("path param validation: primitive with multiple path params emits warning"):
    val src =
      """|---
         |apiClients:
         |  Items:
         |    endpoints:
         |      - name: get
         |        method: GET
         |        path: /api/items/:category/:id
         |        request: Int
         |        response: String
         |---
         |""".stripMargin

    val code = JsGen.generate(Parser.parse(src))
    assert(code.contains("// [ssc warning] apiClient Items.get: 2 path params but request type 'Int' provides at most one value"))

  test("JS typed route clients encode and decode through generated codecs"):
    assume(hasNode, "node not available")
    val code = JsGen.generate(Parser.parse(source))
    val harness =
      """
        |globalThis.fetch = async function(url, init) {
        |  const responseBody =
        |    url === "/api/messages"
        |      ? (init.method === "GET" ? [{id: 1, text: "Ada"}] : {id: 2, text: JSON.parse(init.body).text})
        |      : {id: 3, text: "Grace"};
        |  return {
        |    ok: true,
        |    status: 200,
        |    headers: { get: function() { return "application/json"; } },
        |    text: async function() { return JSON.stringify(responseBody); }
        |  };
        |};
        |
        |(async function() {
        |  const created = await Messages.create(CreateMessage("hello"));
        |  const listed = await Messages.list();
        |  const one = await Messages.get(3);
        |  process.stdout.write(created._type + ":" + created.text + ":" + listed[0]._type + ":" + listed[0].text + ":" + one.id);
        |})().catch(function(e) {
        |  process.stdout.write("ERR:" + (e && e.stack ? e.stack : e));
        |  process.exitCode = 1;
        |});
        |""".stripMargin
    assert(runJs(code + "\n" + harness) == "Message:hello:Message:Ada:3")
