package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x Phase-2 follow-up — server→client notification dispatch.
 *
 *  McpClientCore + McpWsClient now route inbound JSON-RPC notifications
 *  (frames with `method` but no `id`) to a `(method, params) => Unit`
 *  handler registered via `setNotificationHandler`.  Tests assert:
 *  the handler fires when a notification arrives; unregistering it
 *  makes subsequent notifications no-ops; responses still pair with
 *  pending requests independently of the handler. */
class McpNotificationTest extends AnyFunSuite with Matchers:

  test("McpClientCore: setNotificationHandler receives inbound notifications"):
    val received = new LinkedBlockingQueue[(String, ujson.Value)]()
    val client   = new McpClientCore(_ => ())
    client.setNotificationHandler { (m, p) => received.offer((m, p)) }

    val notif = """{"jsonrpc":"2.0","method":"notifications/progress","params":{"step":1}}"""
    client.dispatchResponse(notif) shouldBe true

    val got = received.poll(1_000L, TimeUnit.MILLISECONDS)
    got should not be null
    got._1 shouldBe "notifications/progress"
    got._2("step").num shouldBe 1

  test("McpClientCore: no handler → notification silently dropped (returns false)"):
    val client = new McpClientCore(_ => ())
    client.dispatchResponse("""{"jsonrpc":"2.0","method":"x","params":{}}""") shouldBe false

  test("McpClientCore: response routing still works alongside a notification handler"):
    val sent = scala.collection.mutable.ArrayBuffer.empty[String]
    val client = new McpClientCore(s => sent += s)
    client.setNotificationHandler { (_, _) => /* unused */ }

    val t = new Thread((() => {
      Thread.sleep(30)
      val id = ujson.read(sent.head)("id").num.toLong
      client.dispatchResponse(s"""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
    }): Runnable, "fake-mcp-server-with-notif-handler")
    t.setDaemon(true); t.start()

    val r = client.request("test", ujson.Obj(), 2_000L)
    r.isRight shouldBe true
    r.toOption.get("ok").bool shouldBe true

  test("McpClientCore: handler exception doesn't kill the dispatch path"):
    var called = false
    val client = new McpClientCore(_ => ())
    client.setNotificationHandler { (_, _) =>
      called = true
      throw new RuntimeException("boom — should be swallowed")
    }
    // dispatchResponse returns false because the handler threw, but the
    // reader thread keeps going — second notification still dispatches.
    client.dispatchResponse("""{"jsonrpc":"2.0","method":"a","params":{}}""") shouldBe false
    called shouldBe true
    called = false
    client.setNotificationHandler { (_, _) => called = true }  // replace handler
    client.dispatchResponse("""{"jsonrpc":"2.0","method":"b","params":{}}""") shouldBe true
    called shouldBe true

  test("McpClientCore: setNotificationHandler(null) unregisters the handler"):
    var called = false
    val client = new McpClientCore(_ => ())
    client.setNotificationHandler { (_, _) => called = true }
    client.dispatchResponse("""{"jsonrpc":"2.0","method":"x","params":{}}""")
    called shouldBe true
    called = false
    client.setNotificationHandler(null)
    client.dispatchResponse("""{"jsonrpc":"2.0","method":"y","params":{}}""") shouldBe false
    called shouldBe false
