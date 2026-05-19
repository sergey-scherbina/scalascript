package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — Pagination: all four list endpoints (tools/list,
 *  resources/list, resources/templates/list, prompts/list) honour the
 *  same cursor protocol.  Default pageSize=0 → pagination disabled
 *  (all items returned in one page, no nextCursor), preserving
 *  pre-v1.17 behaviour. */
class McpPaginationTest extends AnyFunSuite with Matchers:

  test("paginate: pageSize <= 0 → everything in one page, no nextCursor"):
    val (page, next) = McpProtocol.paginate(List(1, 2, 3, 4, 5), None, 0)
    page shouldBe List(1, 2, 3, 4, 5)
    next shouldBe None

  test("paginate: pageSize 2 splits a 5-item list into 3 pages"):
    val items = List("a", "b", "c", "d", "e")
    val (p1, n1) = McpProtocol.paginate(items, None,         2)
    val (p2, n2) = McpProtocol.paginate(items, n1,           2)
    val (p3, n3) = McpProtocol.paginate(items, n2,           2)
    p1 shouldBe List("a", "b")
    p2 shouldBe List("c", "d")
    p3 shouldBe List("e")
    n1.isDefined shouldBe true
    n2.isDefined shouldBe true
    n3 shouldBe None

  test("paginate: garbage cursor falls back to offset 0"):
    val (page, _) = McpProtocol.paginate(List(1, 2, 3), Some("not-a-number"), 2)
    page shouldBe List(1, 2)

  test("default pageSize is 0 — all 4 list endpoints emit no nextCursor"):
    val builder = new McpServerBuilder
    (1 to 5).foreach(i => builder.tool(s"t$i", None, ujson.Obj("type" -> "object"), _ =>
      ToolHandlerResult(List(McpProtocol.textContent("ok")), false)))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1))
    val js = ujson.read(reply.trim)
    js("result")("tools").arr.length shouldBe 5
    js("result").obj.contains("nextCursor") shouldBe false

  test("tools/list paginates when pageSize is set"):
    val builder = new McpServerBuilder
    (1 to 5).foreach(i => builder.tool(s"t$i", None, ujson.Obj(), _ =>
      ToolHandlerResult(Nil, false)))
    builder.setPageSize(2)
    val r1 = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1))
    val js1 = ujson.read(r1.trim)
    js1("result")("tools").arr.length        shouldBe 2
    js1("result")("tools")(0)("name").str    shouldBe "t1"
    val cursor1 = js1("result")("nextCursor").str
    val r2 = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj("cursor" -> cursor1), ujson.Num(2))
    val js2 = ujson.read(r2.trim)
    js2("result")("tools").arr.length        shouldBe 2
    js2("result")("tools")(0)("name").str    shouldBe "t3"
    val cursor2 = js2("result")("nextCursor").str
    val r3 = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj("cursor" -> cursor2), ujson.Num(3))
    val js3 = ujson.read(r3.trim)
    js3("result")("tools").arr.length          shouldBe 1
    js3("result")("tools")(0)("name").str      shouldBe "t5"
    js3("result").obj.contains("nextCursor")   shouldBe false  // last page

  test("resources/list paginates"):
    val builder = new McpServerBuilder
    (1 to 3).foreach(i =>
      builder.resource(s"file:///r$i", None, None, uri =>
        ResourceHandlerResult(uri, Nil)))
    builder.setPageSize(2)
    val r1 = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesList, ujson.Obj(), ujson.Num(1))
    val js1 = ujson.read(r1.trim)
    js1("result")("resources").arr.length  shouldBe 2
    js1("result")("nextCursor").str        shouldBe "2"

  test("resources/templates/list paginates"):
    val builder = new McpServerBuilder
    (1 to 3).foreach(i =>
      builder.resourceTemplate(s"file:///t$i/{path}", None, None, None, uri =>
        ResourceHandlerResult(uri, Nil)))
    builder.setPageSize(1)
    val r1 = McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesTemplatesList,
      ujson.Obj(), ujson.Num(1))
    val js1 = ujson.read(r1.trim)
    js1("result")("resourceTemplates").arr.length         shouldBe 1
    js1("result")("resourceTemplates")(0)("uriTemplate").str shouldBe "file:///t1/{path}"
    js1("result")("nextCursor").str                        shouldBe "1"

  test("prompts/list paginates"):
    val builder = new McpServerBuilder
    (1 to 4).foreach(i =>
      builder.prompt(s"p$i", None, Nil, _ =>
        PromptHandlerResult(None, Nil)))
    builder.setPageSize(3)
    val r1 = McpServerCore.dispatch(builder, McpProtocol.Method.PromptsList, ujson.Obj(), ujson.Num(1))
    val js1 = ujson.read(r1.trim)
    js1("result")("prompts").arr.length shouldBe 3
    js1("result")("nextCursor").str     shouldBe "3"
    val r2 = McpServerCore.dispatch(builder,
      McpProtocol.Method.PromptsList, ujson.Obj("cursor" -> "3"), ujson.Num(2))
    val js2 = ujson.read(r2.trim)
    js2("result")("prompts").arr.length        shouldBe 1
    js2("result").obj.contains("nextCursor")   shouldBe false

  test("cursor encode/decode is a round trip"):
    val cs = List(0, 1, 10, 100, 999)
    cs.foreach { n =>
      McpProtocol.decodeCursor(McpProtocol.encodeCursor(n)) shouldBe Some(n)
    }
    McpProtocol.decodeCursor("oops") shouldBe None
