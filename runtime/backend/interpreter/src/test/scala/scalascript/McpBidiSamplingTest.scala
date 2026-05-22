package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** v1.17.x — bidirectional sampling: server-initiated JSON-RPC requests
 *  routed to a client `onRequest` handler whose result is shipped back
 *  as a Response.  Tests cover both directions of the pending-id
 *  correlation: client-side dispatch + reply, server-side pending map
 *  + caller unblock. */
class McpBidiSamplingTest extends AnyFunSuite with Matchers:

  // ── Client-side request dispatch ────────────────────────────────────

  test("McpClientCore: setRequestHandler handles server-initiated requests"):
    val outbound = new LinkedBlockingQueue[String]()
    val client   = new McpClientCore(outbound.offer)
    client.setRequestHandler { (method, params) =>
      method shouldBe "sampling/createMessage"
      val p: String = params("prompt").str
      ujson.Obj("content" -> ("LLM reply for: " + p))
    }
    // Simulate server pushing a Request frame
    val req = """{"jsonrpc":"2.0","method":"sampling/createMessage","params":{"prompt":"hi"},"id":42}"""
    client.dispatchResponse(req) shouldBe true

    val sent = outbound.poll(500L, TimeUnit.MILLISECONDS)
    sent should not be null
    val resp = ujson.read(sent.trim)
    resp("id").num shouldBe 42
    resp("result")("content").str shouldBe "LLM reply for: hi"
    resp.obj.contains("error") shouldBe false

  test("McpClientCore: handler exception → JSON-RPC error response"):
    val outbound = new LinkedBlockingQueue[String]()
    val client   = new McpClientCore(outbound.offer)
    client.setRequestHandler { (_, _) => throw new RuntimeException("LLM unavailable") }
    val req = """{"jsonrpc":"2.0","method":"sampling/createMessage","params":{},"id":7}"""
    client.dispatchResponse(req) shouldBe true
    val sent = outbound.poll(500L, TimeUnit.MILLISECONDS)
    val resp = ujson.read(sent.trim)
    resp("id").num shouldBe 7
    resp("error")("code").num shouldBe JsonRpc.ErrorCode.InternalError
    resp("error")("message").str should include ("LLM unavailable")

  test("McpClientCore: no handler → MethodNotFound response"):
    val outbound = new LinkedBlockingQueue[String]()
    val client   = new McpClientCore(outbound.offer)
    val req = """{"jsonrpc":"2.0","method":"sampling/createMessage","params":{},"id":9}"""
    client.dispatchResponse(req) shouldBe false
    val sent = outbound.poll(500L, TimeUnit.MILLISECONDS)
    val resp = ujson.read(sent.trim)
    resp("error")("code").num shouldBe JsonRpc.ErrorCode.MethodNotFound
    resp("error")("message").str should include ("sampling/createMessage")

  // ── Server-side request roundtrip ──────────────────────────────────

  test("McpServerBuilder.request: roundtrip via in-process client"):
    val builder = new McpServerBuilder
    // Wire client + server through a "wire" — server's broadcaster
    // writes frames into the client's dispatchResponse path; client's
    // own writer feeds back into builder.routeInboundResponse via the
    // dispatchResponse for Response frames.
    val client = new McpClientCore(line => {
      JsonRpc.parse(line) match
        case Right(resp: JsonRpc.Message.Response) => builder.routeInboundResponse(resp)
        case _                                     => ()
    })
    client.setRequestHandler { (_, params) =>
      val q: String = params("q").str
      ujson.Obj("answer" -> ("got " + q))
    }
    builder.addSubscriber(client.dispatchResponse)

    // Server fires a request; the response comes back through the same wire.
    val r = builder.request("sampling/createMessage", ujson.Obj("q" -> "ping"), 2_000L)
    r.isRight shouldBe true
    r.toOption.get("answer").str shouldBe "got ping"

  test("McpServerBuilder.request: client with no handler → MethodNotFound back to server"):
    val builder = new McpServerBuilder
    // Wire client's writer to route Response frames back to the builder
    // so MethodNotFound replies unblock the server-side caller.
    val client = new McpClientCore(line => {
      JsonRpc.parse(line) match
        case Right(resp: JsonRpc.Message.Response) => builder.routeInboundResponse(resp)
        case _                                     => ()
    })
    builder.addSubscriber(client.dispatchResponse)
    // No handler on client → it sends MethodNotFound; server unblocks
    // with Left(MethodNotFound), not a timeout.
    val r = builder.request("sampling/createMessage", ujson.Obj(), 1_000L)
    r.isLeft shouldBe true
    r.swap.toOption.get.code shouldBe JsonRpc.ErrorCode.MethodNotFound

  test("McpServerBuilder.request: empty-subscriber path returns immediate Left"):
    val builder = new McpServerBuilder
    val r = builder.request("anything", ujson.Obj(), 100L)
    r.isLeft shouldBe true
    r.swap.toOption.get.message should include ("no active client subscribers")

  test("McpServerBuilder.request: timeout fires when client never replies"):
    val builder = new McpServerBuilder
    // Subscriber that swallows frames — no response ever sent back.
    builder.addSubscriber(_ => ())
    val r = builder.request("test", ujson.Obj(), 100L)
    r.isLeft shouldBe true
    r.swap.toOption.get.message should include ("timed out")
