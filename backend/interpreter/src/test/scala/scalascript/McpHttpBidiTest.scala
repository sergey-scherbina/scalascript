package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.{Executors, LinkedBlockingQueue, TimeUnit}

/** v1.17.x — HTTP bidirectional sampling end-to-end.
 *
 *  Server-side `McpServerBuilder.request` broadcasts a Request to all
 *  active SSE subscribers (clients with an open `/events` stream).  The
 *  client's `McpHttpClient` SSE reader picks up the Request, runs the
 *  registered handler, and POSTs the JSON-RPC Response back to `/mcp`.
 *  Server's `handleHttpRequest` routes inbound Response frames through
 *  `routeInboundResponse(resp)`, unblocking the original caller.
 *
 *  Drives a real `com.sun.net.httpserver` instance with two endpoints:
 *  POST /mcp (handleHttpRequest) + GET /mcp/events (SSE stream that
 *  registers the writer as a subscriber). */
class McpHttpBidiTest extends AnyFunSuite with Matchers:

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort; s.close(); p

  test("HTTP bidi: srv.request → client.onRequest → response routed back"):
    val port    = freePort()
    val builder = new McpServerBuilder
    val server  = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    // POST /mcp — drives handleHttpRequest, which routes Request frames
    // through dispatch and Response frames through routeInboundResponse.
    server.createContext("/mcp", { ex =>
      val body  = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      val reply = McpServerCore.handleHttpRequest(builder, body)
      val bytes = if reply.isEmpty then Array.emptyByteArray else reply.getBytes("UTF-8")
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(if reply.isEmpty then 204 else 200, bytes.length.toLong)
      if bytes.length > 0 then
        val os = ex.getResponseBody; os.write(bytes); os.close()
      else ex.close()
    })

    // GET /mcp/events — open SSE stream; register the writer as a
    // subscriber for builder.notify / builder.request broadcasts.
    server.createContext("/mcp/events", { ex =>
      ex.getResponseHeaders.set("Content-Type", "text/event-stream")
      ex.getResponseHeaders.set("Cache-Control", "no-cache")
      ex.sendResponseHeaders(200, 0L)
      val os = ex.getResponseBody
      val unsub = builder.addSubscriber { line =>
        try
          os.write(s"data: ${line.stripSuffix("\n")}\n\n".getBytes("UTF-8"))
          os.flush()
        catch case _: Throwable => ()
      }
      // Hold the stream open ~3s — long enough for the test exchange.
      try Thread.sleep(3_000L) catch case _: Throwable => ()
      finally
        unsub()
        try os.close() catch case _: Throwable => ()
    })
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()

    try
      // Open the client, register an onRequest handler.
      val client = new McpHttpClient(s"http://127.0.0.1:$port/mcp", 5_000L)
      val seenRequests = new LinkedBlockingQueue[(String, ujson.Value)]()
      client.setRequestHandler { (method, params) =>
        seenRequests.offer((method, params))
        ujson.Obj("content" -> s"echo: ${params("q").str}")
      }

      // Give the SSE reader thread time to connect & subscribe.
      Thread.sleep(300)

      // Server fires a request; should round-trip through the SSE stream
      // + client handler + POST-back.
      val r = builder.request("sampling/createMessage",
                              ujson.Obj("q" -> "hello"), 2_500L)
      r.isRight shouldBe true
      r.toOption.get("content").str shouldBe "echo: hello"

      // Handler was invoked exactly once with the expected payload.
      val seen = seenRequests.poll(100L, TimeUnit.MILLISECONDS)
      seen should not be null
      seen._1 shouldBe "sampling/createMessage"

      client.close()
    finally server.stop(0)
