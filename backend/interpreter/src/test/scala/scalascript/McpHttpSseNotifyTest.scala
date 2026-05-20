package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** v1.17.x — HTTP SSE GET stream client-side reader.
 *
 *  McpHttpClient's `setNotificationHandler` spins up a daemon thread that
 *  opens `<url>/events` as an SSE stream and dispatches `data: <json>\n\n`
 *  frames to the registered handler.  The server side (installHttpRoute)
 *  matches: serveMcp(Transport.Http(port, path)) registers a GET
 *  `<path>/events` endpoint that subscribes to builder.notify
 *  broadcasts.
 *
 *  This test drives the client against a hand-rolled
 *  com.sun.net.httpserver SSE endpoint — bypasses the interpreter
 *  WebServer setup that's hard to drive from a parallel test JVM. */
class McpHttpSseNotifyTest extends AnyFunSuite with Matchers:

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort; s.close(); p

  test("McpHttpClient: SSE reader dispatches data frames as notifications"):
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    // POST /mcp — handle initialize handshake so makeHttpClient doesn't
    // throw before we get a chance to subscribe.  (Test exercises only
    // notifications, not call paths.)
    server.createContext("/mcp", { ex =>
      val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
      val reply = McpServerCore.handleHttpRequest(new McpServerBuilder, body)
      val bytes =
        if reply.isEmpty then Array.emptyByteArray
        else reply.getBytes("UTF-8")
      ex.getResponseHeaders.set("Content-Type", "application/json")
      ex.sendResponseHeaders(if reply.isEmpty then 204 else 200, bytes.length.toLong)
      if bytes.length > 0 then
        val os = ex.getResponseBody; os.write(bytes); os.close()
      else ex.close()
    })

    // GET /mcp/events — SSE endpoint.  Writes one notification frame and
    // holds the connection open for ~1 second so the client has time to
    // parse it.
    val sentRef = new AtomicReference[String](null)
    server.createContext("/mcp/events", { ex =>
      ex.getResponseHeaders.set("Content-Type", "text/event-stream")
      ex.getResponseHeaders.set("Cache-Control", "no-cache")
      ex.sendResponseHeaders(200, 0L)  // chunked
      val os = ex.getResponseBody
      try
        val frame = sentRef.get()
        if frame != null then
          os.write(s"data: $frame\n\n".getBytes("UTF-8"))
          os.flush()
        // Keep the stream open briefly so the client can read.
        Thread.sleep(500L)
      catch case _: Throwable => ()
      finally
        try os.close() catch case _: Throwable => ()
    })

    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    try
      val notif = """{"jsonrpc":"2.0","method":"notifications/progress","params":{"step":3}}"""
      sentRef.set(notif)

      val client = new McpHttpClient(s"http://127.0.0.1:$port/mcp", 5_000L)
      val seen = new LinkedBlockingQueue[(String, ujson.Value)]()
      client.setNotificationHandler { (m, p) => seen.offer((m, p)); () }

      val got = seen.poll(3_000L, TimeUnit.MILLISECONDS)
      got should not be null
      got._1 shouldBe "notifications/progress"
      got._2("step").num shouldBe 3
      client.close()
    finally server.stop(0)
