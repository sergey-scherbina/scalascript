package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x — Logging: client controls server log floor via
 *  `logging/setLevel`; server emits `notifications/message` log lines
 *  at or above that floor. */
class McpLoggingTest extends AnyFunSuite with Matchers:

  test("initialize advertises logging capability"):
    val reply = McpServerCore.dispatch(new McpServerBuilder,
      McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(1),
      "srv", "1.0.0")
    val caps = ujson.read(reply.trim)("result")("capabilities")
    caps.obj.contains("logging") shouldBe true

  test("logging/setLevel updates the floor"):
    val builder = new McpServerBuilder
    builder.loggingLevel shouldBe "info"  // default
    val reply = McpServerCore.dispatch(builder, McpProtocol.Method.LoggingSetLevel,
      ujson.Obj("level" -> "warning"), ujson.Num(1))
    ujson.read(reply.trim)("result").obj shouldBe empty
    builder.loggingLevel shouldBe "warning"

  test("logging/setLevel rejects unknown level"):
    val reply = McpServerCore.dispatch(new McpServerBuilder,
      McpProtocol.Method.LoggingSetLevel, ujson.Obj("level" -> "shouting"), ujson.Num(2))
    val js = ujson.read(reply.trim)
    js("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
    js("error")("message").str should include ("shouting")

  test("logging/setLevel rejects missing level"):
    val reply = McpServerCore.dispatch(new McpServerBuilder,
      McpProtocol.Method.LoggingSetLevel, ujson.Obj(), ujson.Num(3))
    ujson.read(reply.trim)("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams

  test("log: lines at or above the floor emit notifications/message"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    // Default floor is "info" — debug filtered, info+ passes.
    builder.log("debug", ujson.Str("noise"))
    received.poll(50L, TimeUnit.MILLISECONDS) shouldBe null
    builder.log("info", ujson.Str("hello"))
    val f1 = received.poll(200L, TimeUnit.MILLISECONDS)
    f1 should not be null
    val js1 = ujson.read(f1.trim)
    js1("method").str shouldBe McpProtocol.Method.LogMessage
    js1("params")("level").str shouldBe "info"
    js1("params")("data").str shouldBe "hello"
    builder.log("error", ujson.Str("bad"))
    val f2 = received.poll(200L, TimeUnit.MILLISECONDS)
    ujson.read(f2.trim)("params")("level").str shouldBe "error"

  test("log: raising the floor silences lower levels"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    // Client raises floor to "error" — info / warning silenced.
    McpServerCore.dispatch(builder, McpProtocol.Method.LoggingSetLevel,
      ujson.Obj("level" -> "error"), ujson.Num(1))
    builder.log("info",    ujson.Str("filtered"))
    builder.log("warning", ujson.Str("also-filtered"))
    received.poll(50L, TimeUnit.MILLISECONDS) shouldBe null
    builder.log("error",   ujson.Str("delivered"))
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    frame should not be null
    ujson.read(frame.trim)("params")("data").str shouldBe "delivered"

  test("log: optional logger field"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.log("info", ujson.Str("msg"), Some("my-component"))
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    val js = ujson.read(frame.trim)
    js("params")("logger").str shouldBe "my-component"
