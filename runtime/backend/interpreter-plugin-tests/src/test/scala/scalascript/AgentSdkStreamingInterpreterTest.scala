package scalascript

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.compiler.plugin.http.HttpInterpreterPlugin
import scalascript.compiler.plugin.json.JsonInterpreterPlugin
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class AgentSdkStreamingInterpreterTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port = 19694
  private var server: HttpServer = scala.compiletime.uninitialized
  private val requests = ConcurrentLinkedQueue[String]()
  private val acceptHeaders = ConcurrentLinkedQueue[String]()
  private val authHeaders = ConcurrentLinkedQueue[String]()
  private val count = AtomicInteger(0)

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/v1/chat/completions", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      requests.add(body)
      acceptHeaders.add(Option(exchange.getRequestHeaders.getFirst("Accept")).getOrElse(""))
      authHeaders.add(Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse(""))
      count.incrementAndGet()
      if body.contains(""""model":"stream-http-error-model"""") then
        writeText(exchange, 503, "gateway down")
      else if body.contains(""""model":"stream-error-model"""") then
        writeSse(exchange, List(
          sse("""{"error":{"message":"stream exploded"}}"""),
          done
        ))
      else if body.contains(""""model":"stream-text-model"""") then
        writeSse(exchange, List(
          sse("""{"choices":[{"delta":{"role":"assistant","content":""},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{"content":"Hello "},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{"content":"stream."},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
          done
        ))
      else if body.contains(""""model":"stream-tool-model"""") && body.contains(""""role":"tool"""") then
        writeSse(exchange, List(
          sse("""{"choices":[{"delta":{"content":"Posted via stream."},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
          done
        ))
      else if body.contains(""""model":"stream-tool-model"""") then
        writeSse(exchange, streamedToolCall)
      else if body.contains(""""model":"stream-loop-model"""") then
        writeSse(exchange, streamedToolCall)
      else
        writeSse(exchange, List(
          sse("""{"choices":[{"delta":{"content":"fallback"},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
          done
        ))
    )
    server.setExecutor(null)
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("std.agent streams text deltas and returns the final AgentResult"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, collectAgentStream](std/agent.ssc)
         |
         |```scala
         |val streamed = collectAgentStream(
         |  AgentEndpoint("http://localhost:$port", "secret"),
         |  "stream-text-model",
         |  "system",
         |  "stream text",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(streamed.result.stop)
         |println(streamed.result.text)
         |println(streamed.result.operations.length)
         |println(streamed.events.map(e => e.kind + ":" + e.text + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nHello stream.\n0\nTextDelta:Hello |TextDelta:stream.|Stopped:Done"
    requests.peek() should include(""""stream":true""")
    requests.peek() should include(""""model":"stream-text-model"""")
    acceptHeaders.peek() shouldBe "text/event-stream"
    authHeaders.peek() shouldBe "Bearer secret"

  test("std.agent runAgentStream invokes the event callback"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, runAgentStream](std/agent.ssc)
         |
         |```scala
         |val result = runAgentStream(
         |  AgentEndpoint("http://localhost:$port"),
         |  "stream-text-model",
         |  "system",
         |  "stream text",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |) { event =>
         |  if event.kind == "TextDelta" then
         |    println("delta:" + event.text)
         |  if event.kind == "Stopped" then
         |    println("stop-event:" + event.stop)
         |}
         |
         |println("result:" + result.stop + ":" + result.text)
         |```
         |""".stripMargin
    )

    out shouldBe "delta:Hello \ndelta:stream.\nstop-event:Done\nresult:Done:Hello stream."

  test("std.agent streams tool-call deltas, dispatches the tool, and continues"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, collectAgentStream, toolOk, objectSchema](std/agent.ssc)
         |[jsonValue, jStr, jNum, jField, jObj](std/json.ssc)
         |
         |```scala
         |val schema = objectSchema(
         |  jObj(List(
         |    jField("amount", jObj(List(jField("type", jStr("integer"))))),
         |    jField("memo", jObj(List(jField("type", jStr("string")))))
         |  )),
         |  List("amount", "memo")
         |)
         |
         |val postTransaction = agentTool("post_transaction", "Post one transaction.", schema) { argsJson =>
         |  val args = jsonValue(argsJson)
         |  toolOk(jObj(List(
         |    jField("status", jStr("posted")),
         |    jField("amount", jNum(args.get("amount").asInt.toString)),
         |    jField("memo", jStr(args.get("memo").asString))
         |  )))
         |}
         |
         |val streamed = collectAgentStream(
         |  AgentEndpoint("http://localhost:$port"),
         |  "stream-tool-model",
         |  "system",
         |  "post office supplies",
         |  List(postTransaction),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(streamed.result.stop)
         |println(streamed.result.text)
         |println(streamed.result.operations.length)
         |println(streamed.result.operations.map(op => op.tool + ":" + op.argsJson.contains("office supplies") + ":" + op.resultJson.contains("posted")).mkString(","))
         |println(streamed.events.map(e => e.kind + ":" + e.tool + ":" + e.text + ":" + e.argsJson + ":" + e.resultJson.contains("posted") + ":" + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out should include("Done\nPosted via stream.\n1\npost_transaction:true:true")
    out should include("ToolCallStarted:post_transaction")
    out should include("""ToolCallDelta:post_transaction::{"amount":""")
    out should include("ToolCallResult:post_transaction")
    out should include("TextDelta::Posted via stream.::false:")
    requests.size() shouldBe 2
    requests.toArray(new Array[String](0)).last should include(""""role":"tool"""")
    requests.toArray(new Array[String](0)).last should include("office supplies")

  test("std.agent reports OpenAI SSE error frames as Error"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, collectAgentStream](std/agent.ssc)
         |
         |```scala
         |val streamed = collectAgentStream(
         |  AgentEndpoint("http://localhost:$port"),
         |  "stream-error-model",
         |  "system",
         |  "fail",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(streamed.result.stop)
         |println(streamed.result.text.contains("stream exploded"))
         |println(streamed.events.map(e => e.kind + ":" + e.text + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out should include("Error\ntrue")
    out should include("Errored:stream exploded")
    out should include("Stopped:Error")

  test("std.agent reports non-2xx streaming responses as Error"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, collectAgentStream](std/agent.ssc)
         |
         |```scala
         |val streamed = collectAgentStream(
         |  AgentEndpoint("http://localhost:$port"),
         |  "stream-http-error-model",
         |  "system",
         |  "fail",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(streamed.result.stop)
         |println(streamed.result.text.contains("HTTP 503"))
         |println(streamed.events.map(e => e.kind + ":" + e.text + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out should include("Error\ntrue")
    out should include("Errored:rozum gateway HTTP 503")
    out should include("Stopped:Error")

  test("std.agent streaming stops cleanly at maxSteps"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, collectAgentStream, toolOk, objectSchema](std/agent.ssc)
         |[jStr, jField, jObj](std/json.ssc)
         |
         |```scala
         |val schema = objectSchema(jObj(List()), List())
         |val t = agentTool("post_transaction", "Post.", schema) { argsJson =>
         |  toolOk(jObj(List(jField("ok", jStr("yes")))))
         |}
         |
         |val streamed = collectAgentStream(
         |  AgentEndpoint("http://localhost:$port"),
         |  "stream-loop-model",
         |  "system",
         |  "loop",
         |  List(t),
         |  RunOptions(maxSteps = 1)
         |)
         |
         |println(streamed.result.stop)
         |println(streamed.result.operations.length)
         |println(streamed.result.transcriptJson.contains("\\\"role\\\":\\\"tool\\\""))
         |println(streamed.events.map(e => e.kind + ":" + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out shouldBe "MaxSteps\n1\ntrue\nToolCallStarted:|ToolCallDelta:|ToolCallDelta:|ToolCallDelta:|ToolCallResult:|Stopped:MaxSteps"
    requests.size() shouldBe 1

  test("rozum-agent-streaming example runs end-to-end with its fake SSE gateway"):
    val src = os.read(TestPaths.repoRoot / "examples" / "rozum-agent-streaming.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse(src))
    ps.flush()
    val out = buf.toString.trim
    out should include("tool:post_transaction")
    out should include("tool-result-error:false")
    out should include("delta:Posted via stream.")
    out should endWith("stopped:Done\nDone\nPosted via stream.\n1")

  private def streamedToolCall: List[String] =
    List(
      sse("""{"choices":[{"delta":{"role":"assistant","content":null},"finish_reason":null}]}"""),
      sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"post_transaction","arguments":""}}]},"finish_reason":null}]}"""),
      sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"amount\":"}}]},"finish_reason":null}]}"""),
      sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"125,\"memo\":\"office"}}]},"finish_reason":null}]}"""),
      sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":" supplies\"}"}}]},"finish_reason":null}]}"""),
      sse("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""),
      done
    )

  private def sse(data: String): String =
    s"data: $data\n\n"

  private def done: String =
    "data: [DONE]\n\n"

  private def writeSse(exchange: HttpExchange, frames: List[String]): Unit =
    exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
    val os = exchange.getResponseBody
    frames.foreach { frame =>
      os.write(frame.getBytes(StandardCharsets.UTF_8))
      os.flush()
    }
    os.close()

  private def writeText(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "text/plain")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()

  private def reset(): Unit =
    requests.clear()
    acceptHeaders.clear()
    authHeaders.clear()
    count.set(0)

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Streaming Test\n\n" + source))
    ps.flush()
    buf.toString.trim
