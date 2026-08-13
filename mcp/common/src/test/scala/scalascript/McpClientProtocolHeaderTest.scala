package scalascript

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** `MCP-Protocol-Version` on every HTTP request after negotiation.
 *
 *  2025-06-18 made this MANDATORY and 2026-07-28 kept it; our HTTP client sent
 *  it on none. A header is only observable from the other end, so this stands
 *  up a real server and reads what actually arrived — `grep`ping the builder
 *  for `.header(...)` would have asserted that a line of code exists, which is
 *  not the same claim. */
class McpClientProtocolHeaderTest extends AnyFunSuite with Matchers:

  /** A server that records the headers it was sent and answers anything. */
  private def recordingServer(): (HttpServer, String, ConcurrentLinkedQueue[String]) =
    val seen   = ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/mcp", { ex =>
      seen.add(Option(ex.getRequestHeaders.getFirst(McpProtocol.Header.ProtocolVersion))
                 .getOrElse("<absent>"))
      ex.getRequestBody.readAllBytes()
      val body = ujson.write(ujson.Obj(
        "jsonrpc" -> "2.0", "id" -> 1,
        "result"  -> ujson.Obj("resultType" -> McpProtocol.ResultTypeComplete))).getBytes("UTF-8")
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, body.length.toLong)
      ex.getResponseBody.write(body)
      ex.getResponseBody.close()
    })
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    (server, s"http://127.0.0.1:${server.getAddress.getPort}/mcp", seen)

  test("the HTTP client sends MCP-Protocol-Version on an ordinary request"):
    val (server, url, seen) = recordingServer()
    try
      val client = McpHttpClient(url, 3_000L)
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), 3_000L)
      seen.size                 should be >= 1
      seen.peek should not be "<absent>"
      // Before `connect()` the client has settled on nothing, so it states the
      // legacy revision — which is the era it would in fact have spoken.
      seen.peek shouldBe McpProtocol.ProtocolVersion
      client.close()
    finally server.stop(0)

  test("after negotiating the modern era it states THAT version, not the legacy one"):
    // The header must track what the client actually speaks. A constant would
    // pass the case above and be wrong here, which is why both exist.
    val (server, url, seen) = recordingServer()
    try
      val client = McpHttpClient(url, 3_000L)
      client.connect("test-client", "0.1.0", 3_000L)
      seen.clear()
      client.request(McpProtocol.Method.ToolsList, ujson.Obj(), 3_000L)
      val stated = seen.peek
      stated should not be "<absent>"
      stated shouldBe (client.currentEra match
        case McpProtocol.Era.Modern => McpProtocol.ModernProtocolVersion
        case _                      => McpProtocol.ProtocolVersion)
      client.close()
    finally server.stop(0)
