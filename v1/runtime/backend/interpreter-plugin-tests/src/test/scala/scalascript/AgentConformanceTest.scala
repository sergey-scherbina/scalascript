package scalascript

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.compiler.plugin.http.HttpInterpreterPlugin
import scalascript.compiler.plugin.json.JsonInterpreterPlugin
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Golden-transcript conformance for `std.agent` (the "mock gateway" task).
 *
 *  Where `AgentSdkInterpreterTest` scripts the fake gateway by request *content*
 *  (branch on model name), this suite drives it from a **recorded, ordered
 *  sequence of model responses** — one per loop round-trip, popped FIFO. That is
 *  the "recorded ModelResponses" the agent-sdk spec asks for (specs/agent-sdk.md,
 *  Conformance §): a golden transcript is a canned response list, and each test
 *  asserts the resulting run's STRUCTURE (stop reason, executed ops, request
 *  sequence, transcript shape) — never exact model prose.
 *
 *  Three golden transcripts: (1) a single tool-call loop, (2) a multi-turn run
 *  (two sequential tool round-trips before the model stops), and (3) the error
 *  path (a non-2xx gateway turn). The loop's only seam is the endpoint URL, so
 *  the mock is an in-process HttpServer — no real model, no network. */
class AgentConformanceTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port = 19694
  private var server: HttpServer = scala.compiletime.uninitialized

  /** The recorded transcript: (httpStatus, rawOpenAiJsonBody) played back in order. */
  private val script   = ConcurrentLinkedQueue[(Int, String)]()
  /** Every request body the loop sent, in order — for structural assertions. */
  private val requests = ConcurrentLinkedQueue[String]()

  /** Reset + load a golden transcript (the recorded model responses for one run). */
  private def record(responses: (Int, String)*): Unit =
    script.clear()
    requests.clear()
    responses.foreach(script.add)

  // ── recorded model-response shapes (the real /v1/chat/completions wire form) ──
  private def toolCall(id: String, name: String, argsJson: String): String =
    s"""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"$argsJson"}}]}}]}"""
  private def stop(text: String): String =
    s"""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"$text"}}]}"""

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/v1/chat/completions", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      requests.add(body)
      val (status, response) =
        Option(script.poll()).getOrElse(500 -> """{"error":"transcript exhausted"}""")
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

  /** A markdown agent module: imports + one `scala` fence carrying an always-succeed
   *  tool (dispatch/transcript is what we assert, not the tool's own logic) plus `body`. */
  private def doc(body: String): String =
    s"""
       |[AgentEndpoint, RunOptions, agentTool, runAgent, toolOk, objectSchema](std/agent.ssc)
       |[jStr, jField, jObj](std/json.ssc)
       |
       |```scala
       |val postTransaction = agentTool("post_transaction", "Post one transaction.", objectSchema(jObj(List()), List())) { argsJson =>
       |  toolOk(jObj(List(jField("status", jStr("posted")))))
       |}
       |$body
       |```
       |""".stripMargin

  test("golden transcript 1 — tool-use loop (tool_call → dispatch → stop)"):
    record(
      200 -> toolCall("call_1", "post_transaction", """{\"amount\":125,\"memo\":\"office supplies\"}"""),
      200 -> stop("Posted the transaction.")
    )

    val out = run(doc(
      s"""val result = runAgent(
         |  AgentEndpoint("http://localhost:$port", "secret"),
         |  "conformance-model", "system prompt", "post office supplies",
         |  List(postTransaction), RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.operations.length)
         |println(result.operations.map(op => op.tool).mkString(","))
         |println(result.transcriptJson.contains("\\\"role\\\":\\\"tool\\\""))""".stripMargin
    ))

    out shouldBe "Done\n1\npost_transaction\ntrue"
    requests.size() shouldBe 2
    val reqs = requests.toArray(new Array[String](0))
    reqs(0) should include(""""tool_choice":"auto"""")           // turn 1: the ask, with tools
    reqs(1) should include(""""role":"tool"""")                  // turn 2: carries the tool result
    reqs(1) should include(""""tool_call_id":"call_1"""")

  test("golden transcript 2 — multi-turn (two sequential tool round-trips)"):
    record(
      200 -> toolCall("call_a", "post_transaction", """{\"amount\":1,\"memo\":\"first\"}"""),
      200 -> toolCall("call_b", "post_transaction", """{\"amount\":2,\"memo\":\"second\"}"""),
      200 -> stop("All done.")
    )

    val out = run(doc(
      s"""val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "conformance-model", "system", "do two things",
         |  List(postTransaction), RunOptions(maxSteps = 5)
         |)
         |println(result.stop)
         |println(result.operations.length)
         |println(result.operations.map(op => op.tool).mkString(","))""".stripMargin
    ))

    out shouldBe "Done\n2\npost_transaction,post_transaction"
    requests.size() shouldBe 3                                    // 3 model round-trips = multi-turn
    val last = requests.toArray(new Array[String](0)).last
    last should include(""""tool_call_id":"call_a"""")           // final request carries BOTH turns
    last should include(""""tool_call_id":"call_b"""")

  test("golden transcript 3 — error path (non-2xx gateway turn)"):
    record(503 -> "gateway down")

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, runAgent](std/agent.ssc)
         |
         |```scala
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "conformance-model", "system", "fail", List(), RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text.contains("HTTP 503"))
         |```
         |""".stripMargin
    )

    out shouldBe "Error\ntrue"
    requests.size() shouldBe 1                                    // stops at the failed turn

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Test\n\n" + source))
    ps.flush()
    buf.toString.trim
