package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x — Progress notifications.  Client opts in by including
 *  `_meta.progressToken` (string or number) on the request; server
 *  emits matching `notifications/progress` frames during handler
 *  execution via `srv.notifyProgress(progress, total)`. */
class McpProgressTest extends AnyFunSuite with Matchers:

  test("notifyProgress emits the matching progressToken from _meta"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.tool("long_task", None, ujson.Obj(), { _ =>
      builder.notifyProgress(1.0, Some(3.0))
      builder.notifyProgress(2.0, Some(3.0))
      builder.notifyProgress(3.0, Some(3.0))
      ToolHandlerResult(List(McpProtocol.textContent("done")), isError = false)
    })

    val params = ujson.Obj(
      "name"      -> "long_task",
      "arguments" -> ujson.Obj(),
      "_meta"     -> ujson.Obj("progressToken" -> "watch-1")
    )
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(7))
    // Final response was the tool result, not a progress frame.
    ujson.read(reply.trim)("result")("content")(0)("text").str shouldBe "done"

    // Three progress notifications fired before the final response.
    val frames = (0 until 3).map(_ => received.poll(200L, TimeUnit.MILLISECONDS)).toList
    frames.foreach(_ should not be null)
    val parsed = frames.map(f => ujson.read(f.trim))
    parsed.foreach(p => p("method").str shouldBe McpProtocol.Method.Progress)
    parsed.foreach(p => p("params")("progressToken").str shouldBe "watch-1")
    parsed.map(_("params")("progress").num) shouldBe Vector(1.0, 2.0, 3.0)
    parsed.foreach(p => p("params")("total").num shouldBe 3.0)

  test("notifyProgress is no-op when client didn't send progressToken"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.tool("test", None, ujson.Obj(), { _ =>
      // No _meta on the params → notifyProgress should silently no-op.
      builder.notifyProgress(50.0)
      ToolHandlerResult(List(McpProtocol.textContent("ok")), isError = false)
    })

    val params = ujson.Obj("name" -> "test", "arguments" -> ujson.Obj())
    McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(1))
    received.poll(50L, TimeUnit.MILLISECONDS) shouldBe null

  test("progressToken can be numeric"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.tool("test", None, ujson.Obj(), { _ =>
      builder.notifyProgress(42.0)
      ToolHandlerResult(List(McpProtocol.textContent("ok")), isError = false)
    })

    val params = ujson.Obj(
      "name"      -> "test",
      "arguments" -> ujson.Obj(),
      "_meta"     -> ujson.Obj("progressToken" -> 999)
    )
    McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(1))
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    frame should not be null
    ujson.read(frame.trim)("params")("progressToken").num shouldBe 999

  test("progress without total omits the total field"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.tool("test", None, ujson.Obj(), { _ =>
      builder.notifyProgress(0.5)  // no total
      ToolHandlerResult(Nil, isError = false)
    })
    val params = ujson.Obj(
      "name" -> "test", "arguments" -> ujson.Obj(),
      "_meta" -> ujson.Obj("progressToken" -> "tk")
    )
    McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall, params, ujson.Num(1))
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    val js = ujson.read(frame.trim)
    js("params")("progress").num shouldBe 0.5
    js("params").obj.contains("total") shouldBe false
