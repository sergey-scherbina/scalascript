package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** v1.17.x — Completion: client → server `completion/complete` request
 *  for a prompt argument or resource-template variable.  Server returns
 *  up to 100 suggestions; the spec mandates graceful degradation when
 *  no handler is registered (reply with empty values, not an error). */
class McpCompletionTest extends AnyFunSuite with Matchers:

  test("completionResult caps at 100 values and sets hasMore"):
    val small = McpProtocol.completionResult(List("a", "b", "c"))
    small("completion")("values").arr.length shouldBe 3
    small("completion")("total").num         shouldBe 3.0
    small("completion")("hasMore").bool      shouldBe false

    val big = McpProtocol.completionResult((1 to 150).map(_.toString).toList)
    big("completion")("values").arr.length shouldBe 100
    big("completion")("total").num         shouldBe 150.0
    big("completion")("hasMore").bool      shouldBe true

  test("parseCompletionRef: prompt and resource shapes"):
    val pr = McpProtocol.parseCompletionRef(
      ujson.Obj("type" -> "ref/prompt", "name" -> "summarize"))
    val rr = McpProtocol.parseCompletionRef(
      ujson.Obj("type" -> "ref/resource", "uri" -> "file:///{path}"))
    pr shouldBe Some(McpProtocol.CompletionRef.PromptRef("summarize"))
    rr shouldBe Some(McpProtocol.CompletionRef.ResourceRef("file:///{path}"))

  test("parseCompletionRef: garbage → None"):
    McpProtocol.parseCompletionRef(ujson.Obj())                              shouldBe None
    McpProtocol.parseCompletionRef(ujson.Obj("type" -> "ref/prompt"))        shouldBe None  // missing name
    McpProtocol.parseCompletionRef(ujson.Obj("type" -> "ref/garbage", "x" -> 1)) shouldBe None
    McpProtocol.parseCompletionRef(ujson.Str("scalar"))                      shouldBe None

  test("initialize advertises completions capability"):
    val builder = new McpServerBuilder
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.Initialize,
      ujson.Obj(), ujson.Num(1), "srv", "1.0.0")
    val caps = ujson.read(reply.trim)("result")("capabilities")
    caps.obj.contains("completions") shouldBe true

  test("completion/complete invokes the registered prompt handler"):
    val builder = new McpServerBuilder
    builder.completionForPrompt("summarize", "topic", value =>
      List("auth", "audit", "automation").filter(_.startsWith(value))
    )
    val params = ujson.Obj(
      "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "summarize"),
      "argument" -> ujson.Obj("name" -> "topic", "value" -> "au")
    )
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      params, ujson.Num(1))
    val vals = ujson.read(reply.trim)("result")("completion")("values").arr
    vals.map(_.str).toList shouldBe List("auth", "audit", "automation")

  test("completion/complete invokes the registered resource handler"):
    val builder = new McpServerBuilder
    builder.completionForResource("file:///{path}", "path", value =>
      List("home/u/a.txt", "home/u/b.txt").filter(_.startsWith(value))
    )
    val params = ujson.Obj(
      "ref"      -> ujson.Obj("type" -> "ref/resource", "uri" -> "file:///{path}"),
      "argument" -> ujson.Obj("name" -> "path", "value" -> "home")
    )
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      params, ujson.Num(1))
    val vals = ujson.read(reply.trim)("result")("completion")("values").arr
    vals.map(_.str).toList shouldBe List("home/u/a.txt", "home/u/b.txt")

  test("missing handler → empty values (graceful, not an error)"):
    val builder = new McpServerBuilder
    val params = ujson.Obj(
      "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "unknown"),
      "argument" -> ujson.Obj("name" -> "x", "value" -> "")
    )
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      params, ujson.Num(1))
    val js = ujson.read(reply.trim)
    js.obj.contains("error") shouldBe false
    js("result")("completion")("values").arr.length shouldBe 0
    js("result")("completion")("hasMore").bool      shouldBe false

  test("handler throws → empty values (failsafe)"):
    val builder = new McpServerBuilder
    builder.completionForPrompt("buggy", "x", _ => throw new RuntimeException("kaboom"))
    val params = ujson.Obj(
      "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "buggy"),
      "argument" -> ujson.Obj("name" -> "x", "value" -> "")
    )
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      params, ujson.Num(1))
    val js = ujson.read(reply.trim)
    js.obj.contains("error") shouldBe false
    js("result")("completion")("values").arr.length shouldBe 0

  test("invalid params shapes return InvalidParams"):
    val builder = new McpServerBuilder
    val r1 = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      ujson.Str("not-an-object"), ujson.Num(1))
    val r2 = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      ujson.Obj("argument" -> ujson.Obj("name" -> "x", "value" -> "")),  // no ref
      ujson.Num(1))
    val r3 = McpServerCore.dispatch(builder, McpProtocol.Method.CompletionComplete,
      ujson.Obj(
        "ref"      -> ujson.Obj("type" -> "ref/prompt", "name" -> "p"),
        "argument" -> ujson.Obj("value" -> "")  // no name
      ),
      ujson.Num(1))
    ujson.read(r1.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
    ujson.read(r2.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
    ujson.read(r3.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
