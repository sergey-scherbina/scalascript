package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — MCP 2025-03 tool + resource annotations.  Closes the
 *  last spec gap: UI hints (`title`, `readOnlyHint`, `destructiveHint`,
 *  `idempotentHint`, `openWorldHint`) on tools; `audience` + `priority`
 *  on resources / resource templates. */
class McpAnnotationsTest extends AnyFunSuite with Matchers:

  // ─── ToolAnnotations.toJson ─────────────────────────────────────

  test("ToolAnnotations.toJson emits only set fields"):
    val ann = McpProtocol.ToolAnnotations(
      title           = Some("My Tool"),
      readOnlyHint    = Some(true),
      destructiveHint = Some(false))
    val js = ann.toJson
    js("title").str            shouldBe "My Tool"
    js("readOnlyHint").bool    shouldBe true
    js("destructiveHint").bool shouldBe false
    js.obj.contains("idempotentHint") shouldBe false
    js.obj.contains("openWorldHint")  shouldBe false

  test("ToolAnnotations.isEmpty"):
    McpProtocol.ToolAnnotations().isEmpty shouldBe true
    McpProtocol.ToolAnnotations(title = Some("x")).isEmpty shouldBe false

  // ─── ResourceAnnotations.toJson ──────────────────────────────────

  test("ResourceAnnotations.toJson emits audience + priority"):
    val ann = McpProtocol.ResourceAnnotations(
      audience = List("user", "assistant"), priority = Some(0.7))
    val js = ann.toJson
    js("audience").arr.map(_.str).toList shouldBe List("user", "assistant")
    js("priority").num                    shouldBe 0.7

  test("ResourceAnnotations.isEmpty + drops empty audience"):
    McpProtocol.ResourceAnnotations().isEmpty shouldBe true
    val partial = McpProtocol.ResourceAnnotations(audience = Nil, priority = Some(1.0))
    partial.toJson.obj.contains("audience") shouldBe false
    partial.toJson("priority").num shouldBe 1.0

  // ─── tools/list emits annotations ────────────────────────────────

  test("tools/list emits annotations when registered with them"):
    val builder = new McpServerBuilder
    builder.tool("dangerous", Some("Wipes things"), ujson.Obj("type" -> "object"),
      _ => ToolHandlerResult(Nil, false),
      annotations = Some(McpProtocol.ToolAnnotations(
        title           = Some("Wipe!"),
        readOnlyHint    = Some(false),
        destructiveHint = Some(true),
        idempotentHint  = Some(false),
        openWorldHint   = Some(false))))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1))
    val tool = ujson.read(reply.trim)("result")("tools")(0)
    tool("annotations")("title").str               shouldBe "Wipe!"
    tool("annotations")("destructiveHint").bool    shouldBe true
    tool("annotations")("readOnlyHint").bool       shouldBe false

  test("tools/list omits annotations key when none set"):
    val builder = new McpServerBuilder
    builder.tool("plain", None, ujson.Obj(),
      _ => ToolHandlerResult(Nil, false))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1))
    val tool = ujson.read(reply.trim)("result")("tools")(0)
    tool.obj.contains("annotations") shouldBe false

  test("tools/list omits annotations key when annotations record is empty"):
    val builder = new McpServerBuilder
    builder.tool("plain", None, ujson.Obj(),
      _ => ToolHandlerResult(Nil, false),
      annotations = Some(McpProtocol.ToolAnnotations()))
    val tool = ujson.read(
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsList,
        ujson.Obj(), ujson.Num(1)).trim
    )("result")("tools")(0)
    tool.obj.contains("annotations") shouldBe false

  // ─── resources/list emits annotations ────────────────────────────

  test("resources/list emits annotations when registered"):
    val builder = new McpServerBuilder
    builder.resource("file:///r1", Some("R1"), Some("text/plain"),
      uri => ResourceHandlerResult(uri, Nil),
      annotations = Some(McpProtocol.ResourceAnnotations(
        audience = List("user"), priority = Some(0.5))))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesList, ujson.Obj(), ujson.Num(1))
    val r = ujson.read(reply.trim)("result")("resources")(0)
    r("annotations")("audience").arr.head.str shouldBe "user"
    r("annotations")("priority").num          shouldBe 0.5

  test("resources/templates/list emits annotations"):
    val builder = new McpServerBuilder
    builder.resourceTemplate("file:///{path}", None, None, None,
      uri => ResourceHandlerResult(uri, Nil),
      annotations = Some(McpProtocol.ResourceAnnotations(
        priority = Some(0.9))))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesTemplatesList, ujson.Obj(), ujson.Num(1))
    val t = ujson.read(reply.trim)("result")("resourceTemplates")(0)
    t("annotations")("priority").num shouldBe 0.9

  // ─── Backwards-compat: tools without annotations still work ──────

  test("legacy tool registration (no annotations arg) still works"):
    val builder = new McpServerBuilder
    builder.tool("legacy", Some("desc"), ujson.Obj(),
      _ => ToolHandlerResult(List(McpProtocol.textContent("ok")), false))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ToolsList, ujson.Obj(), ujson.Num(1))
    val tool = ujson.read(reply.trim)("result")("tools")(0)
    tool("name").str        shouldBe "legacy"
    tool("description").str shouldBe "desc"
