package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.{Executors, LinkedBlockingQueue, TimeUnit}

/** v1.17.x — Streamable-HTTP: SSE-as-response-body for tools/call.
 *
 *  When the client sends `Accept: application/json, text/event-stream`
 *  the server may respond with `Content-Type: text/event-stream` and
 *  stream the body as `data: <json>\n\n` frames — interleaving
 *  progress notifications with the final response.  Client side:
 *  McpHttpClient.request inspects Content-Type and switches between
 *  inline JSON parse and SSE-stream parse.
 *
 *  This test drives a hand-rolled HttpServer endpoint that emits a
 *  scripted sequence: two progress notifications + a final tools/call
 *  result — and asserts the client (a) returns the result correctly
 *  and (b) dispatches the progress notifications via
 *  setNotificationHandler during the same request(...) call. */
class McpStreamableHttpTest extends AnyFunSuite with Matchers:

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort; s.close(); p

  test("McpHttpClient: streamable response interleaves notifications with the final result"):
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    server.createContext("/mcp", { ex =>
      val req = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      // We only stream the response if the client said it wanted to.
      val accept = Option(ex.getRequestHeaders.getFirst("Accept")).getOrElse("")
      val id = ujson.read(req)("id").num.toLong
      if accept.toLowerCase.contains("text/event-stream") then
        ex.getResponseHeaders.set("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0L)  // chunked
        val os = ex.getResponseBody
        try
          // Two progress notifications, then the final result.
          val n1 = """{"jsonrpc":"2.0","method":"progress","params":{"step":1}}"""
          val n2 = """{"jsonrpc":"2.0","method":"progress","params":{"step":2}}"""
          val r  = s"""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"done"}],"isError":false}}"""
          os.write(s"data: $n1\n\n".getBytes("UTF-8")); os.flush()
          Thread.sleep(20)
          os.write(s"data: $n2\n\n".getBytes("UTF-8")); os.flush()
          Thread.sleep(20)
          os.write(s"data: $r\n\n".getBytes("UTF-8")); os.flush()
        catch case _: Throwable => ()
        finally try os.close() catch case _: Throwable => ()
      else
        // Fallback: plain JSON (initialize handshake path).
        val resp = s"""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"${McpProtocol.ProtocolVersion}"}}"""
        val bytes = resp.getBytes("UTF-8")
        ex.getResponseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length.toLong)
        val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    try
      val client = new McpHttpClient(s"http://127.0.0.1:$port/mcp", 5_000L)
      val progress = new LinkedBlockingQueue[(String, ujson.Value)]()
      client.setNotificationHandler { (m, p) => progress.offer((m, p)); () }
      // The handler is registered via setNotificationHandler.  When the
      // server streams two progress notifications + one final response,
      // request(...) drains the SSE stream inline:
      val r = client.request(McpProtocol.Method.ToolsCall, ujson.Obj("name" -> "long_task"))
      r.isRight shouldBe true
      r.toOption.get("content")(0)("text").str shouldBe "done"

      // Both progress notifications were dispatched mid-request.
      val p1 = progress.poll(500L, TimeUnit.MILLISECONDS)
      val p2 = progress.poll(500L, TimeUnit.MILLISECONDS)
      p1 should not be null
      p2 should not be null
      p1._1 shouldBe "progress"; p1._2("step").num shouldBe 1
      p2._1 shouldBe "progress"; p2._2("step").num shouldBe 2

      client.close()
    finally server.stop(0)

  test("McpHttpClient: plain-JSON response still works when server doesn't stream"):
    // Same client, server that always returns plain JSON regardless of Accept.
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/mcp", { ex =>
      val req = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      val id  = ujson.read(req)("id").num.toLong
      val resp = s"""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}"""
      val bytes = resp.getBytes("UTF-8")
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      val os = ex.getResponseBody; os.write(bytes); os.close()
    })
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    try
      val client = new McpHttpClient(s"http://127.0.0.1:$port/mcp", 5_000L)
      val r = client.request("ping", ujson.Obj())
      r.isRight shouldBe true
      r.toOption.get("ok").bool shouldBe true
      client.close()
    finally server.stop(0)
