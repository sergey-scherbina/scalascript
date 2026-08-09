package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x — Resource subscriptions: `resources/subscribe` /
 *  `resources/unsubscribe` + `notifications/resources/updated` push.
 *
 *  Server tracks subscribed URIs in `subscribedResources`; the
 *  `onResourceSubscribe` hook fires for each inbound subscribe
 *  (typical wiring: spin up a file watcher in the hook).  When the
 *  user calls `notifyResourceUpdate(uri)`, only subscribed URIs are
 *  pushed — unsubscribed URIs are no-op. */
class McpResourceSubscriptionTest extends AnyFunSuite with Matchers:

  test("dispatch: resources/subscribe returns success + fires onResourceSubscribe"):
    val builder = new McpServerBuilder
    val seen = new LinkedBlockingQueue[String]()
    builder.setOnResourceSubscribe { uri => seen.offer(uri); () }
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesSubscribe,
      ujson.Obj("uri" -> "file:///bar.md"),
      ujson.Num(1))
    val js = ujson.read(reply.trim)
    js("id").num shouldBe 1
    js("result").obj shouldBe empty
    seen.poll(200L, TimeUnit.MILLISECONDS) shouldBe "file:///bar.md"

  test("dispatch: resources/subscribe rejects missing uri"):
    val reply = McpServerCore.dispatch(new McpServerBuilder,
      McpProtocol.Method.ResourcesSubscribe, ujson.Obj(), ujson.Num(2))
    val js = ujson.read(reply.trim)
    js("error")("code").num shouldBe JsonRpc.ErrorCode.InvalidParams
    js("error")("message").str should include ("uri")

  test("dispatch: resources/unsubscribe symmetric to subscribe"):
    val builder = new McpServerBuilder
    val unsubs  = new LinkedBlockingQueue[String]()
    builder.setOnResourceUnsubscribe { uri => unsubs.offer(uri); () }
    // First subscribe so the unsubscribe has something to remove.
    McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesSubscribe,
      ujson.Obj("uri" -> "file:///x.md"), ujson.Num(3))
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesUnsubscribe,
      ujson.Obj("uri" -> "file:///x.md"),
      ujson.Num(4))
    ujson.read(reply.trim)("result").obj shouldBe empty
    unsubs.poll(200L, TimeUnit.MILLISECONDS) shouldBe "file:///x.md"

  test("notifyResourceUpdate: pushes only when uri is subscribed"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)

    // Not subscribed yet — no-op.
    builder.notifyResourceUpdate("file:///not-subscribed.md")
    received.poll(50L, TimeUnit.MILLISECONDS) shouldBe null

    // Subscribe + push — should arrive.
    McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesSubscribe,
      ujson.Obj("uri" -> "file:///watched.md"), ujson.Num(1))
    builder.notifyResourceUpdate("file:///watched.md")
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    frame should not be null
    val js = ujson.read(frame.trim)
    js("method").str shouldBe McpProtocol.Method.ResourcesUpdated
    js("params")("uri").str shouldBe "file:///watched.md"

    // Unsubscribe + push — silenced again.
    McpServerCore.dispatch(builder, McpProtocol.Method.ResourcesUnsubscribe,
      ujson.Obj("uri" -> "file:///watched.md"), ujson.Num(2))
    builder.notifyResourceUpdate("file:///watched.md")
    received.poll(50L, TimeUnit.MILLISECONDS) shouldBe null

  test("initialize: advertises subscribe capability on resources"):
    val builder = new McpServerBuilder
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(99),
      "test-srv", "1.0.0")
    val caps = ujson.read(reply.trim)("result")("capabilities")
    caps("resources")("subscribe").bool shouldBe true

  test("onResourceSubscribe hook exception doesn't kill the dispatch path"):
    val builder = new McpServerBuilder
    builder.setOnResourceSubscribe { _ => throw new RuntimeException("watcher failed") }
    // Subscribe still replies success even though the user hook threw —
    // we treat the hook as best-effort.
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.ResourcesSubscribe,
      ujson.Obj("uri" -> "file:///x.md"), ujson.Num(1))
    ujson.read(reply.trim)("result").obj shouldBe empty
