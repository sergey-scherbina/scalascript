package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x Phase-2 follow-up — server-side `srv.notify(method, params)`.
 *
 *  McpServerBuilder gains a thread-safe subscriber set; transports
 *  register their writers via `addSubscriber(write)` and unregister
 *  on close.  `notify(method, params)` broadcasts a JSON-RPC
 *  notification frame to every subscriber.
 *
 *  These tests drive the broadcaster directly (transport-agnostic).
 *  End-to-end Stdio→client and Ws→client paths are exercised in
 *  McpEndToEndTest (existing) and via the McpHttpSseNotifyTest. */
class McpServerNotifyTest extends AnyFunSuite with Matchers:

  test("notify with no subscribers is a no-op (doesn't throw)"):
    val builder = new McpServerBuilder
    builder.notify("notifications/progress", ujson.Obj("step" -> 1))
    succeed

  test("notify reaches a single subscriber"):
    val builder  = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(received.offer)
    builder.notify("notifications/progress", ujson.Obj("step" -> 2))

    val frame = received.poll(500L, TimeUnit.MILLISECONDS)
    frame should not be null
    val js = ujson.read(frame.trim)
    js("method").str shouldBe "notifications/progress"
    js("params")("step").num shouldBe 2

  test("notify broadcasts to every subscriber"):
    val builder = new McpServerBuilder
    val r1 = new LinkedBlockingQueue[String]()
    val r2 = new LinkedBlockingQueue[String]()
    val r3 = new LinkedBlockingQueue[String]()
    builder.addSubscriber(r1.offer)
    builder.addSubscriber(r2.offer)
    builder.addSubscriber(r3.offer)
    builder.notify("ping", ujson.Obj())
    r1.poll(500L, TimeUnit.MILLISECONDS) should not be null
    r2.poll(500L, TimeUnit.MILLISECONDS) should not be null
    r3.poll(500L, TimeUnit.MILLISECONDS) should not be null

  test("unsubscribe stops further notifications to that subscriber"):
    val builder = new McpServerBuilder
    val r1 = new LinkedBlockingQueue[String]()
    val r2 = new LinkedBlockingQueue[String]()
    builder.addSubscriber(r1.offer)
    val u2 = builder.addSubscriber(r2.offer)
    u2()  // r2 unsubscribes
    builder.notify("after-unsubscribe", ujson.Obj())
    r1.poll(500L, TimeUnit.MILLISECONDS) should not be null
    r2.poll(50L,  TimeUnit.MILLISECONDS) shouldBe null

  test("subscriber that throws doesn't block other subscribers"):
    val builder = new McpServerBuilder
    val received = new LinkedBlockingQueue[String]()
    builder.addSubscriber(_ => throw new RuntimeException("kaboom"))
    builder.addSubscriber(received.offer)
    builder.notify("test", ujson.Obj())
    received.poll(500L, TimeUnit.MILLISECONDS) should not be null

  test("end-to-end: server→client notification roundtrip via McpClientCore"):
    // Wire builder.addSubscriber's writer to the client's dispatch path:
    // server pushes a notification → reader thread parses → handler fires.
    val builder = new McpServerBuilder
    val client  = new McpClientCore(_ => ())
    builder.addSubscriber(client.dispatchResponse)
    val seen = new LinkedBlockingQueue[(String, ujson.Value)]()
    client.setNotificationHandler((m, p) => { seen.offer((m, p)); () })
    builder.notify("notifications/cancelled", ujson.Obj("requestId" -> 7))
    val got = seen.poll(500L, TimeUnit.MILLISECONDS)
    got should not be null
    got._1 shouldBe "notifications/cancelled"
    got._2("requestId").num shouldBe 7
