package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.net.InetSocketAddress
import com.sun.net.httpserver.{HttpServer, HttpExchange, HttpHandler}
import java.util.concurrent.atomic.AtomicInteger

/** v1.17.x — Iter NN: McpHttpClient.setOn401Handler triggers a
 *  single re-auth retry when an outbound request comes back 401.
 *  Uses an embedded JDK HttpServer fixture to simulate AS-protected
 *  endpoints without needing a real WebServer integration. */
class McpHttp401ReAuthTest extends AnyFunSuite with Matchers:

  /** Spin up a one-shot HTTP server that:
   *    - first POST → 401 (simulates an expired bearer)
   *    - second POST with `Authorization: Bearer refreshed-token` →
   *      200 with the JSON-RPC reply
   *  Returns the live server (caller stops it) + its base URL. */
  private def fixture(): (HttpServer, String) =
    val srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val attempts = new AtomicInteger(0)
    srv.createContext("/mcp", new HttpHandler {
      def handle(ex: HttpExchange): Unit =
        val attempt = attempts.incrementAndGet()
        val authHdr = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
        if attempt == 1 then
          // First attempt — pretend the bearer expired.
          val resp = """{"error":"invalid_token"}"""
          val bytes = resp.getBytes("UTF-8")
          ex.getResponseHeaders.set("WWW-Authenticate", """Bearer realm="mcp", error="invalid_token"""")
          ex.sendResponseHeaders(401, bytes.length.toLong)
          ex.getResponseBody.write(bytes); ex.getResponseBody.close()
        else
          // Second attempt — must carry the refreshed bearer.
          if !authHdr.contains("refreshed-token") then
            ex.sendResponseHeaders(403, -1); ex.getResponseBody.close()
          else
            // Mirror the request id into a fake JSON-RPC response.
            val raw = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
            val js = ujson.read(raw)
            val replyId = js("id")
            val reply = ujson.Obj(
              "jsonrpc" -> "2.0",
              "id"      -> replyId,
              "result"  -> ujson.Obj("ok" -> true)
            ).render()
            val bytes = reply.getBytes("UTF-8")
            ex.getResponseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.length.toLong)
            ex.getResponseBody.write(bytes); ex.getResponseBody.close()
    })
    srv.setExecutor(null)  // default executor
    srv.start()
    (srv, s"http://127.0.0.1:${srv.getAddress.getPort}/mcp")

  test("McpHttpClient: 401 with re-auth handler → retry with fresh token succeeds"):
    val (srv, url) = fixture()
    try
      val client = new McpHttpClient(url, 5000L)
      client.setBearerToken(Some("stale-token"))
      var refreshed = 0
      client.setOn401Handler { () =>
        refreshed += 1
        Some("refreshed-token")
      }
      // Drive a request — the server returns 401 → client refreshes → retry succeeds.
      client.request(McpProtocol.Method.Ping, ujson.Obj()) match
        case Right(js) => js("ok").bool shouldBe true
        case Left(e)   => fail(s"got error: ${e.message}")
      refreshed shouldBe 1
    finally srv.stop(0)

  test("McpHttpClient: 401 with no re-auth handler → 401 propagates"):
    val (srv, url) = fixture()
    try
      val client = new McpHttpClient(url, 5000L)
      client.setBearerToken(Some("any-stale-token"))
      // No on401Handler wired — default returns None
      client.request(McpProtocol.Method.Ping, ujson.Obj()) match
        case Left(e) => e.message should include ("401")
        case Right(_) => fail("should have failed with 401")
    finally srv.stop(0)

  test("McpHttpClient: re-auth handler returning None → 401 propagates"):
    val (srv, url) = fixture()
    try
      val client = new McpHttpClient(url, 5000L)
      client.setBearerToken(Some("stale-token"))
      client.setOn401Handler(() => None)
      client.request(McpProtocol.Method.Ping, ujson.Obj()) match
        case Left(e) => e.message should include ("401")
        case Right(_) => fail("should have failed")
    finally srv.stop(0)
