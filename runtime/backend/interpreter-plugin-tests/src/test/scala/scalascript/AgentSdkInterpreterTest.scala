package scalascript

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

class AgentSdkInterpreterTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port = 19693
  private var server: HttpServer = scala.compiletime.uninitialized
  private val requests = ConcurrentLinkedQueue[String]()
  private val authHeaders = ConcurrentLinkedQueue[String]()
  private val count = AtomicInteger(0)

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/v1/chat/completions", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      requests.add(body)
      authHeaders.add(Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse(""))
      val n = count.incrementAndGet()
      val (status, response) =
        if body.contains(""""model":"direct-model"""") then
          200 -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Direct answer."}}]}"""
        else if body.contains(""""model":"error-model"""") then
          503 -> """gateway down"""
        else if body.contains(""""model":"unknown-tool-model"""") && body.contains(""""role":"tool"""") then
          200 -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Recovered from tool error."}}]}"""
        else if body.contains(""""model":"unknown-tool-model"""") then
          200 -> """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_unknown","type":"function","function":{"name":"missing_tool","arguments":"{}"}}]}}]}"""
        else if body.contains(""""model":"handler-error-model"""") && body.contains(""""role":"tool"""") then
          200 -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Handler error handled."}}]}"""
        else if body.contains(""""model":"handler-error-model"""") then
          200 -> """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_bad_args","type":"function","function":{"name":"post_transaction","arguments":"{}"}}]}}]}"""
        else if body.contains(""""model":"loop-model"""") then
          200 -> """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_loop","type":"function","function":{"name":"post_transaction","arguments":"{\"amount\":1,\"memo\":\"loop\"}"}}]}}]}"""
        else if n == 1 then
          200 -> """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"post_transaction","arguments":"{\"amount\":125,\"memo\":\"office supplies\"}"}}]}}]}"""
        else
          200 -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Posted the transaction."}}]}"""
      val bytes = response.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length)
      val os = exchange.getResponseBody
      os.write(bytes)
      os.close()
    )
    server.setExecutor(null)
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("std.agent runs a tool-call loop against an OpenAI-compatible gateway"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, runAgent, toolOk, objectSchema](std/agent.ssc)
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
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port", "secret"),
         |  "local-tool-model",
         |  "system prompt",
         |  "post office supplies",
         |  List(postTransaction),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |println(result.operations.map(op => op.tool + ":" + op.resultJson.contains("posted")).mkString(","))
         |println(result.transcriptJson.contains("\\\"role\\\":\\\"tool\\\""))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nPosted the transaction.\n1\npost_transaction:true\ntrue"
    count.get() shouldBe 2
    authHeaders.peek() shouldBe "Bearer secret"
    val first = requests.poll()
    val second = requests.poll()
    first should include(""""model":"local-tool-model"""")
    first should include(""""tools":[{"type":"function"""")
    first should include(""""tool_choice":"auto"""")
    second should include(""""role":"tool"""")
    second should include(""""tool_call_id":"call_1"""")
    second should include("office supplies")

  test("std.agent returns direct final text without synthetic tool execution"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, runAgent](std/agent.ssc)
         |
         |```scala
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "direct-model",
         |  "system",
         |  "answer directly",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nDirect answer.\n0"
    authHeaders.peek() shouldBe ""

  test("std.agent feeds unknown tool calls back as tool errors"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, runAgent](std/agent.ssc)
         |
         |```scala
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "unknown-tool-model",
         |  "system",
         |  "call a missing tool",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |println(result.operations.map(op => op.tool + ":" + op.isError).mkString(","))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nRecovered from tool error.\n1\nmissing_tool:true"
    requests.size() shouldBe 2
    requests.toArray(new Array[String](0)).last should include("unknown tool: missing_tool")

  test("std.agent stops cleanly at maxSteps"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, runAgent, toolOk, objectSchema](std/agent.ssc)
         |[jsonValue, jStr, jNum, jField, jObj](std/json.ssc)
         |
         |```scala
         |val schema = objectSchema(jObj(List()), List())
         |val t = agentTool("post_transaction", "Post.", schema) { argsJson =>
         |  toolOk(jObj(List(jField("ok", jStr("yes")))))
         |}
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "loop-model",
         |  "system",
         |  "loop",
         |  List(t),
         |  RunOptions(maxSteps = 1)
         |)
         |println(result.stop)
         |println(result.operations.length)
         |println(result.transcriptJson.contains("\\\"role\\\":\\\"tool\\\""))
         |```
         |""".stripMargin
    )

    out shouldBe "MaxSteps\n1\ntrue"

  test("std.agent feeds handler validation errors back to the model"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, runAgent, toolOk, toolError, objectSchema](std/agent.ssc)
         |[jsonValue, jStr, jField, jObj](std/json.ssc)
         |
         |```scala
         |val schema = objectSchema(jObj(List(jField("amount", jObj(List(jField("type", jStr("integer"))))))), List("amount"))
         |val t = agentTool("post_transaction", "Post.", schema) { argsJson =>
         |  val args = jsonValue(argsJson)
         |  if args.get("amount").isNull then toolError("missing amount")
         |  else toolOk(jObj(List(jField("ok", jStr("yes")))))
         |}
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "handler-error-model",
         |  "system",
         |  "bad args",
         |  List(t),
         |  RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |println(result.operations.map(op => op.isError).mkString(","))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nHandler error handled.\n1\ntrue"
    requests.toArray(new Array[String](0)).last should include("missing amount")

  test("std.agent returns Error on non-2xx gateway status"):
    requests.clear()
    authHeaders.clear()
    count.set(0)

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, runAgent](std/agent.ssc)
         |
         |```scala
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "error-model",
         |  "system",
         |  "fail",
         |  List(),
         |  RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text.contains("HTTP 503"))
         |```
         |""".stripMargin
    )

    out shouldBe "Error\ntrue"

  test("rozum-agent example runs end-to-end with its fake gateway"):
    val src = os.read(TestPaths.repoRoot / "examples" / "rozum-agent.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse(src))
    ps.flush()
    buf.toString.trim should endWith("Done\nPosted the transaction.\n1")

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Test\n\n" + source))
    ps.flush()
    buf.toString.trim
