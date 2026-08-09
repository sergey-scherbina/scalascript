package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — late-2025 MCP spec additions: structured tool output
 *  (outputSchema + structuredContent), audio content, resource_link
 *  content, direct `title` fields on tool/resource/prompt entries. */
class McpLate2025Test extends AnyFunSuite with Matchers:

  // ─── Content variants ────────────────────────────────────────────

  test("audioContent: base64 data + mimeType"):
    val c = McpProtocol.audioContent("AAAA", "audio/wav")
    c("type").str     shouldBe "audio"
    c("data").str     shouldBe "AAAA"
    c("mimeType").str shouldBe "audio/wav"

  test("resourceLinkContent: minimal uri-only"):
    val c = McpProtocol.resourceLinkContent("file:///r")
    c("type").str shouldBe "resource_link"
    c("uri").str  shouldBe "file:///r"
    c.obj.contains("name")        shouldBe false
    c.obj.contains("description") shouldBe false
    c.obj.contains("mimeType")    shouldBe false

  test("resourceLinkContent: full optional metadata"):
    val c = McpProtocol.resourceLinkContent(
      uri = "file:///doc.pdf", name = Some("Doc"),
      description = Some("Spec doc"), mimeType = Some("application/pdf"))
    c("type").str        shouldBe "resource_link"
    c("uri").str         shouldBe "file:///doc.pdf"
    c("name").str        shouldBe "Doc"
    c("description").str shouldBe "Spec doc"
    c("mimeType").str    shouldBe "application/pdf"

  // ─── Tool with outputSchema + structuredContent ──────────────────

  test("tools/list emits outputSchema when registered"):
    val builder = new McpServerBuilder
    builder.tool("calc", Some("Math"), ujson.Obj("type" -> "object"),
      _ => ToolHandlerResult(Nil, false),
      outputSchema = Some(ujson.Obj(
        "type"       -> "object",
        "properties" -> ujson.Obj("result" -> ujson.Obj("type" -> "number")))))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("outputSchema")("type").str shouldBe "object"

  test("tools/call result carries structuredContent when handler returns it"):
    val builder = new McpServerBuilder
    builder.tool("add", None, ujson.Obj(),
      _ => ToolHandlerResult(
        content           = List(McpProtocol.textContent("42")),
        isError           = false,
        structuredContent = Some(ujson.Obj("result" -> 42))),
      outputSchema = Some(ujson.Obj("type" -> "object")))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "add", "arguments" -> ujson.Obj()), ujson.Num(1))
    val res = ujson.read(reply.trim)("result")
    res("content").arr.head("text").str shouldBe "42"
    res("structuredContent")("result").num shouldBe 42.0

  test("tools/call result OMITS structuredContent when handler returns None"):
    val builder = new McpServerBuilder
    builder.tool("plain", None, ujson.Obj(),
      _ => ToolHandlerResult(List(McpProtocol.textContent("ok")), false))
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "plain", "arguments" -> ujson.Obj()), ujson.Num(1))
    val res = ujson.read(reply.trim)("result")
    res.obj.contains("structuredContent") shouldBe false

  // ─── Direct title fields on entries ──────────────────────────────

  test("tools/list emits direct title field"):
    val builder = new McpServerBuilder
    builder.tool("internal_name", None, ujson.Obj(),
      _ => ToolHandlerResult(Nil, false),
      title = Some("Friendly Tool Name"))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("title").str shouldBe "Friendly Tool Name"

  test("resources/list emits direct title field"):
    val builder = new McpServerBuilder
    builder.resource("file:///r", Some("r"), None,
      uri => ResourceHandlerResult(uri, Nil),
      title = Some("Readable Title"))
    val r = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("resources")(0)
    r("title").str shouldBe "Readable Title"

  test("resources/templates/list emits direct title field"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{p}", None, None, None,
      uri => ResourceHandlerResult(uri, Nil),
      title = Some("Template Title"))
    val t = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesTemplatesList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("resourceTemplates")(0)
    t("title").str shouldBe "Template Title"

  test("prompts/list emits direct title field"):
    val builder = new McpServerBuilder
    builder.prompt("internal", None, Nil,
      _ => PromptHandlerResult(None, Nil),
      title = Some("Greet User"))
    val p = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.PromptsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("prompts")(0)
    p("title").str shouldBe "Greet User"

  // ─── Coexistence with annotations + _meta ────────────────────────

  test("tool with title + outputSchema + annotations + _meta — all surface"):
    val builder = new McpServerBuilder
    builder.tool("kitchen-sink", Some("desc"), ujson.Obj("type" -> "object"),
      _ => ToolHandlerResult(Nil, false),
      annotations  = Some(McpProtocol.ToolAnnotations(readOnlyHint = Some(true))),
      meta         = Some(ujson.Obj("vendor" -> "v")),
      title        = Some("Kitchen Sink"),
      outputSchema = Some(ujson.Obj("type" -> "object")))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("name").str               shouldBe "kitchen-sink"
    tool("title").str              shouldBe "Kitchen Sink"
    tool("description").str        shouldBe "desc"
    tool("outputSchema")("type").str shouldBe "object"
    tool("annotations")("readOnlyHint").bool shouldBe true
    tool("_meta")("vendor").str    shouldBe "v"

  // ─── Backwards compat — entries without new fields ───────────────

  test("legacy entries without title/outputSchema work unchanged"):
    val builder = new McpServerBuilder
    builder.tool("plain", Some("d"), ujson.Obj(),
      _ => ToolHandlerResult(Nil, false))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool.obj.contains("title")        shouldBe false
    tool.obj.contains("outputSchema") shouldBe false

  // ─── End-to-end: structured tool output round trip ──────────────

  test("structured tool: outputSchema declaration + structuredContent return"):
    val builder = new McpServerBuilder
    val schema  = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "celsius" -> ujson.Obj("type" -> "number"),
        "city"    -> ujson.Obj("type" -> "string")))
    builder.tool("weather", Some("Get weather"), ujson.Obj("type" -> "object"),
      _ => ToolHandlerResult(
        content           = List(McpProtocol.textContent("22°C in Berlin")),
        isError           = false,
        structuredContent = Some(ujson.Obj("celsius" -> 22, "city" -> "Berlin"))),
      outputSchema = Some(schema),
      title        = Some("Weather"))
    // Listed with the schema
    val tool = ujson.read(McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool("outputSchema")("properties")("celsius")("type").str shouldBe "number"
    // Called with structured payload in the response
    val call = ujson.read(McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "weather", "arguments" -> ujson.Obj()), ujson.Num(2)).trim
    )("result")
    call("structuredContent")("celsius").num shouldBe 22.0
    call("structuredContent")("city").str    shouldBe "Berlin"
