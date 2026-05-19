package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.mcp.*
import java.util.concurrent.LinkedBlockingQueue

/** End-to-end test: a single `McpServerCore.serve` loop running in one
 *  thread, talking to a `McpClientCore` in another, over a pair of
 *  in-process LinkedBlockingQueues.  No OS subprocess — just exercises
 *  the protocol + dispatch + response-routing path together. */
class McpEndToEndTest extends AnyFunSuite with Matchers:

  private def pair(): (() => Option[String], String => Unit, () => Option[String], String => Unit) =
    // Two queues: clientOut → serverIn, serverOut → clientIn.
    // Sentinel `null` on the queue = EOF for the recipient side.
    val toServer = new LinkedBlockingQueue[String]()
    val toClient = new LinkedBlockingQueue[String]()
    def serverRead(): Option[String] = Option(toServer.take()).flatMap(s => if s == "__EOF__" then None else Some(s))
    def serverWrite(s: String): Unit = toClient.put(s)
    def clientRead(): Option[String] = Option(toClient.take()).flatMap(s => if s == "__EOF__" then None else Some(s))
    def clientWrite(s: String): Unit = toServer.put(s)
    (serverRead, serverWrite, clientRead, clientWrite)

  test("end-to-end: client connects, lists, calls, and closes"):
    val builder = new McpServerBuilder
    builder.tool("echo", Some("echo back the msg field"), ujson.Obj("type" -> "object"), args =>
      val msg = args.getOrElse("msg", "").toString
      ToolHandlerResult(List(McpProtocol.textContent(msg)), isError = false)
    )
    builder.resource("file:///hi.txt", Some("hi"), Some("text/plain"), uri =>
      ResourceHandlerResult(uri, List(McpProtocol.textContent("hello from file")))
    )

    val (sRead, sWrite, cRead, cWrite) = pair()

    // Server thread — runs serve(...) until EOF sentinel arrives.
    val serverThread = new Thread((() => {
      McpServerCore.serve(builder, sRead, sWrite, "ssc-mcp-int", "1.0.0")
    }): Runnable, "test-mcp-server")
    serverThread.setDaemon(true); serverThread.start()

    // Client reader thread — pumps server replies into the client.
    val client = new McpClientCore(cWrite)
    val clientReaderThread = new Thread((() => {
      var alive = true
      while alive do
        cRead() match
          case None       => alive = false
          case Some(line) => client.dispatchResponse(line)
    }): Runnable, "test-mcp-client-reader")
    clientReaderThread.setDaemon(true); clientReaderThread.start()

    // Handshake.
    val init = client.request(McpProtocol.Method.Initialize, ujson.Obj(
      "protocolVersion" -> McpProtocol.ProtocolVersion,
      "capabilities"    -> ujson.Obj(),
      "clientInfo"      -> ujson.Obj("name" -> "test-client", "version" -> "0.1")
    ), 5000)
    init.isRight shouldBe true
    init.toOption.get("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
    client.notify("notifications/initialized", ujson.Obj())

    // tools/list
    val tools = client.request(McpProtocol.Method.ToolsList, ujson.Obj(), 5000).toOption.get
    tools("tools").arr.head("name").str shouldBe "echo"

    // tools/call
    val callRes = client.request(McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "echo", "arguments" -> ujson.Obj("msg" -> "hello world")), 5000).toOption.get
    callRes("isError").bool shouldBe false
    callRes("content")(0)("text").str shouldBe "hello world"

    // resources/list + resources/read
    val resList = client.request(McpProtocol.Method.ResourcesList, ujson.Obj(), 5000).toOption.get
    resList("resources").arr.head("uri").str shouldBe "file:///hi.txt"

    val readRes = client.request(McpProtocol.Method.ResourcesRead,
      ujson.Obj("uri" -> "file:///hi.txt"), 5000).toOption.get
    readRes("contents")(0)("text").str shouldBe "hello from file"

    // ping
    val pingRes = client.request(McpProtocol.Method.Ping, ujson.Obj(), 5000)
    pingRes.isRight shouldBe true

    // Shut down — close client, push EOF sentinel to server.
    client.close()
    // Need to signal both queues: toClient EOF stops the reader thread,
    // toServer EOF stops the server thread.  The shared writer helpers
    // accept the sentinel string directly.
    sWrite("__EOF__")     // sentinel goes to toClient — wakes the client reader to exit
    cWrite("__EOF__")     // sentinel goes to toServer — wakes the server serve() loop to exit
    serverThread.join(2000)
    clientReaderThread.join(2000)
    serverThread.isAlive       shouldBe false
    clientReaderThread.isAlive shouldBe false

  test("end-to-end: handler exception becomes isError=true ToolResult"):
    val builder = new McpServerBuilder
    builder.tool("boom", None, ujson.Obj(), _ => throw new RuntimeException("oops"))
    val (sRead, sWrite, cRead, cWrite) = pair()

    val serverThread = new Thread((() => {
      McpServerCore.serve(builder, sRead, sWrite, "ssc-mcp-int", "1.0.0")
    }): Runnable, "test-mcp-server-2")
    serverThread.setDaemon(true); serverThread.start()

    val client = new McpClientCore(cWrite)
    val readerThread = new Thread((() => {
      var alive = true
      while alive do
        cRead() match
          case None       => alive = false
          case Some(line) => client.dispatchResponse(line)
    }): Runnable, "test-mcp-client-reader-2")
    readerThread.setDaemon(true); readerThread.start()

    // Skip explicit initialize — dispatch handles tools/call without it.
    val res = client.request(McpProtocol.Method.ToolsCall,
      ujson.Obj("name" -> "boom", "arguments" -> ujson.Obj()), 5000).toOption.get
    res("isError").bool shouldBe true
    res("content")(0)("text").str shouldBe "oops"

    client.close()
    sWrite("__EOF__")
    cWrite("__EOF__")
