package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — URI templates: parameterized resources like
 *  `file:///{path}` matched against concrete `resources/read` URIs.
 *  Listed via `resources/templates/list`.  Simplified RFC 6570:
 *  `{name}` placeholders match any non-slash segment. */
class McpResourceTemplatesTest extends AnyFunSuite with Matchers:

  test("uriMatchesTemplate: exact match"):
    val r1 = McpServerCore.uriMatchesTemplate("file:///foo.txt", "file:///foo.txt")
    val r2 = McpServerCore.uriMatchesTemplate("file:///foo.txt", "file:///bar.txt")
    r1 shouldBe true
    r2 shouldBe false

  test("uriMatchesTemplate: single placeholder"):
    val a = McpServerCore.uriMatchesTemplate("file:///{path}", "file:///foo.txt")
    val b = McpServerCore.uriMatchesTemplate("file:///{path}", "file:///deep/x")
    val c = McpServerCore.uriMatchesTemplate("file:///{path}", "other:///x")
    a shouldBe true
    b shouldBe false  // slash blocks
    c shouldBe false

  test("uriMatchesTemplate: multiple placeholders"):
    val a = McpServerCore.uriMatchesTemplate("api://{ns}/items/{id}", "api://users/items/42")
    val b = McpServerCore.uriMatchesTemplate("api://{ns}/items/{id}", "api://users/things/42")
    a shouldBe true
    b shouldBe false

  test("uriMatchesTemplate: literal regex chars escaped"):
    val a = McpServerCore.uriMatchesTemplate("foo.bar://{x}", "foo.bar://baz")
    val b = McpServerCore.uriMatchesTemplate("foo.bar://{x}", "fooXbar://baz")
    a shouldBe true
    b shouldBe false  // dot is literal

  test("resources/templates/list returns registered templates"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{path}", Some("Files"), Some("Local files"), Some("text/plain"),
      uri => ResourceHandlerResult(uri, List(McpProtocol.textContent("data:" + uri))))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesTemplatesList,
      ujson.Obj(), ujson.Num(1))
    val js = ujson.read(reply.trim)
    val tpls = js("result")("resourceTemplates").arr
    tpls.length shouldBe 1
    tpls(0)("uriTemplate").str shouldBe "file:///{path}"
    tpls(0)("name").str        shouldBe "Files"
    tpls(0)("description").str shouldBe "Local files"
    tpls(0)("mimeType").str    shouldBe "text/plain"

  test("resources/read falls through to a matching template handler"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{path}", Some("Files"), None, None,
      uri => ResourceHandlerResult(uri,
        List(McpProtocol.textContent("read uri=" + uri))))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesRead,
      ujson.Obj("uri" -> "file:///hello.md"), ujson.Num(1))
    val js = ujson.read(reply.trim)
    js("result")("contents")(0)("text").str shouldBe "read uri=file:///hello.md"

  test("resources/read prefers exact match over template"):
    val builder = new McpServerBuilder
    builder.resource("file:///special.md", None, None,
      _ => ResourceHandlerResult("file:///special.md",
        List(McpProtocol.textContent("from-exact"))))
    builder.resourceTemplate("file:///{path}", None, None, None,
      uri => ResourceHandlerResult(uri,
        List(McpProtocol.textContent("from-template"))))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesRead,
      ujson.Obj("uri" -> "file:///special.md"), ujson.Num(1))
    ujson.read(reply.trim)("result")("contents")(0)("text").str shouldBe "from-exact"

  test("resources/read returns MethodNotFound when neither exact nor template matches"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{path}", None, None, None,
      uri => ResourceHandlerResult(uri, Nil))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesRead,
      ujson.Obj("uri" -> "other:///foo"), ujson.Num(1))
    ujson.read(reply.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound
