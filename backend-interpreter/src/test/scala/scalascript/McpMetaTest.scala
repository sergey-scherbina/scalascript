package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — MCP `_meta` field propagation.  Implementation-defined
 *  metadata MAY be attached to any object; clients ignore keys they
 *  don't recognise.  Closes the last MCP spec gap. */
class McpMetaTest extends AnyFunSuite with Matchers:

  // ─── tools/list _meta ───────────────────────────────────────────

  test("tools/list emits _meta when registered with it"):
    val builder = new McpServerBuilder
    val meta    = ujson.Obj("vendor" -> "acme", "experimental" -> true)
    builder.tool("ext", None, ujson.Obj("type" -> "object"),
      _ => ToolHandlerResult(Nil, false),
      meta = Some(meta))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("_meta")("vendor").str       shouldBe "acme"
    tool("_meta")("experimental").bool shouldBe true

  test("tools/list omits _meta when not registered"):
    val builder = new McpServerBuilder
    builder.tool("plain", None, ujson.Obj(),
      _ => ToolHandlerResult(Nil, false))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool.obj.contains("_meta") shouldBe false

  test("tools/list omits _meta when registered object is empty"):
    val builder = new McpServerBuilder
    builder.tool("empty-meta", None, ujson.Obj(),
      _ => ToolHandlerResult(Nil, false),
      meta = Some(ujson.Obj()))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool.obj.contains("_meta") shouldBe false

  test("tools/list coexists with annotations + _meta"):
    val builder = new McpServerBuilder
    builder.tool("both", Some("d"), ujson.Obj(),
      _ => ToolHandlerResult(Nil, false),
      annotations = Some(McpProtocol.ToolAnnotations(title = Some("T"))),
      meta        = Some(ujson.Obj("vendor" -> "v")))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("annotations")("title").str shouldBe "T"
    tool("_meta")("vendor").str       shouldBe "v"

  // ─── resources/list _meta ───────────────────────────────────────

  test("resources/list emits _meta when registered"):
    val builder = new McpServerBuilder
    builder.resource("file:///r", Some("R"), None,
      uri => ResourceHandlerResult(uri, Nil),
      meta = Some(ujson.Obj("source" -> "git", "rev" -> "abc123")))
    val r = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("resources")(0)
    r("_meta")("source").str shouldBe "git"
    r("_meta")("rev").str    shouldBe "abc123"

  test("resources/templates/list emits _meta"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{p}", None, None, None,
      uri => ResourceHandlerResult(uri, Nil),
      meta = Some(ujson.Obj("template" -> "fs")))
    val t = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesTemplatesList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("resourceTemplates")(0)
    t("_meta")("template").str shouldBe "fs"

  // ─── prompts/list _meta ─────────────────────────────────────────

  test("prompts/list emits _meta when registered"):
    val builder = new McpServerBuilder
    builder.prompt("hello", Some("Greet"), Nil,
      _ => PromptHandlerResult(None, Nil),
      meta = Some(ujson.Obj("category" -> "greeting")))
    val p = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.PromptsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("prompts")(0)
    p("_meta")("category").str shouldBe "greeting"

  test("prompts/list omits _meta when not registered"):
    val builder = new McpServerBuilder
    builder.prompt("plain", None, Nil,
      _ => PromptHandlerResult(None, Nil))
    val p = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.PromptsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("prompts")(0)
    p.obj.contains("_meta") shouldBe false

  // ─── pagination + _meta coexist ─────────────────────────────────

  test("_meta survives pagination — appears on each paginated entry"):
    val builder = new McpServerBuilder
    (1 to 3).foreach(i =>
      builder.tool(s"t$i", None, ujson.Obj(),
        _ => ToolHandlerResult(Nil, false),
        meta = Some(ujson.Obj("index" -> i))))
    builder.setPageSize(2)
    val page1 = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")
    page1("tools").arr.length shouldBe 2
    page1("tools")(0)("_meta")("index").num shouldBe 1.0
    page1("tools")(1)("_meta")("index").num shouldBe 2.0
    val page2 = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj("cursor" -> page1("nextCursor").str), ujson.Num(2)).trim
    )("result")
    page2("tools")(0)("_meta")("index").num shouldBe 3.0

  // ─── Backwards compat ─────────────────────────────────────────

  test("legacy registration without meta still works"):
    val builder = new McpServerBuilder
    builder.tool("legacy", Some("d"), ujson.Obj(),
      _ => ToolHandlerResult(List(McpProtocol.textContent("ok")), false))
    builder.resource("file:///x", None, None,
      uri => ResourceHandlerResult(uri, Nil))
    builder.prompt("p", None, Nil, _ => PromptHandlerResult(None, Nil))
    // All three lists must still render without _meta key.
    val tools = ujson.read(McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")
    val resources = ujson.read(McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesList, ujson.Obj(), ujson.Num(2)).trim
    )("result")("resources")
    val prompts = ujson.read(McpServerCore.dispatch(builder,
      McpProtocol.Method.PromptsList, ujson.Obj(), ujson.Num(3)).trim
    )("result")("prompts")
    tools(0).obj.contains("_meta")     shouldBe false
    resources(0).obj.contains("_meta") shouldBe false
    prompts(0).obj.contains("_meta")   shouldBe false
