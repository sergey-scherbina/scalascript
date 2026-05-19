package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x — list_changed notifications: tells connected clients to
 *  re-fetch the catalog after the server adds / removes a tool /
 *  resource / prompt at runtime.  Matching capability flags
 *  (`<category>.listChanged: true`) are advertised in initialize. */
class McpListChangedTest extends AnyFunSuite with Matchers:

  test("initialize advertises listChanged: true for all three categories"):
    val builder = new McpServerBuilder
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(1),
      "srv", "1.0.0")
    val caps = ujson.read(reply.trim)("result")("capabilities")
    caps("tools")("listChanged").bool     shouldBe true
    caps("resources")("listChanged").bool shouldBe true
    caps("prompts")("listChanged").bool   shouldBe true
    // resources still advertises subscribe alongside listChanged.
    caps("resources")("subscribe").bool   shouldBe true

  test("notifyToolsListChanged pushes notifications/tools/list_changed"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.notifyToolsListChanged()
    val frame = received.poll(200L, TimeUnit.MILLISECONDS)
    frame should not be null
    val js = ujson.read(frame.trim)
    js("method").str shouldBe McpProtocol.Method.ToolsListChanged
    js("params").obj shouldBe empty  // spec: no payload
    js.obj.contains("id") shouldBe false  // notification — no id

  test("notifyResourcesListChanged + notifyPromptsListChanged use their method names"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.notifyResourcesListChanged()
    builder.notifyPromptsListChanged()
    val f1 = received.poll(200L, TimeUnit.MILLISECONDS)
    val f2 = received.poll(200L, TimeUnit.MILLISECONDS)
    f1 should not be null
    f2 should not be null
    ujson.read(f1.trim)("method").str shouldBe McpProtocol.Method.ResourcesListChanged
    ujson.read(f2.trim)("method").str shouldBe McpProtocol.Method.PromptsListChanged

  test("list_changed notifications reach the McpClientCore via dispatchResponse"):
    val builder = new McpServerBuilder
    val client  = new McpClientCore(_ => ())
    val seen = new LinkedBlockingQueue[String]()
    client.setNotificationHandler((m, _) => seen.offer(m))
    builder.addSubscriber(client.dispatchResponse)

    builder.notifyToolsListChanged()
    builder.notifyResourcesListChanged()
    builder.notifyPromptsListChanged()

    val a = seen.poll(200L, TimeUnit.MILLISECONDS)
    val b = seen.poll(200L, TimeUnit.MILLISECONDS)
    val c = seen.poll(200L, TimeUnit.MILLISECONDS)
    Set(a, b, c) shouldBe Set(
      McpProtocol.Method.ToolsListChanged,
      McpProtocol.Method.ResourcesListChanged,
      McpProtocol.Method.PromptsListChanged
    )
