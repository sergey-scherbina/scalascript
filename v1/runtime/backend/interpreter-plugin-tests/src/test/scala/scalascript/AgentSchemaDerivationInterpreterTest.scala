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

class AgentSchemaDerivationInterpreterTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port = 19701
  private var server: HttpServer = scala.compiletime.uninitialized
  private val requests = ConcurrentLinkedQueue[String]()
  private val count = AtomicInteger(0)

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/v1/chat/completions", exchange =>
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      requests.add(body)
      count.incrementAndGet()
      val response =
        if body.contains(""""model":"derived-schema-model"""") && !body.contains(""""role":"tool"""") then
          """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_derived","type":"function","function":{"name":"post_transaction","arguments":"{\"amount\":125,\"memo\":\"office supplies\",\"tags\":[\"office\",\"cash\"],\"approved\":true,\"note\":null}"}}]}}]}"""
        else if body.contains(""""model":"explicit-schema-model"""") && !body.contains(""""role":"tool"""") then
          """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_explicit","type":"function","function":{"name":"post_transaction_explicit","arguments":"{\"mode\":\"fast\"}"}}]}}]}"""
        else if body.contains(""""model":"explicit-schema-model"""") then
          """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Explicit posted."}}]}"""
        else
          """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Derived posted."}}]}"""
      val bytes = response.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length)
      val os = exchange.getResponseBody
      os.write(bytes)
      os.close()
    )
    server.setExecutor(null)
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("agentToolFor derives schema and dispatches typed record arguments"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentSchema, RunOptions, agentToolFor, runAgent, toolOk](std/agent.ssc)
         |[jStr, jNum, jBool, jField, jObj](std/json.ssc)
         |
         |```scala
         |case class PostTransaction(
         |  amount: Int,
         |  memo: String,
         |  tags: List[String],
         |  approved: Boolean,
         |  note: Option[String]
         |) derives AgentSchema
         |
         |val postTransaction = agentToolFor[PostTransaction](
         |  "post_transaction",
         |  "Post one typed transaction.",
         |  summon[AgentSchema[PostTransaction]]
         |) { args =>
         |  val note = args.note match
         |    case Some(value) => value
         |    case None => "none"
         |  toolOk(jObj(List(
         |    jField("status", jStr("posted")),
         |    jField("amount", jNum(args.amount.toString)),
         |    jField("memo", jStr(args.memo)),
         |    jField("tags", jNum(args.tags.length.toString)),
         |    jField("approved", jBool(args.approved)),
         |    jField("note", jStr(note))
         |  )))
         |}
         |
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "derived-schema-model",
         |  "system",
         |  "post office supplies",
         |  List(postTransaction),
         |  RunOptions(maxSteps = 4)
         |)
         |
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |println(result.operations.map(op => op.resultJson.contains("\\\"tags\\\":2")).mkString(","))
         |println(result.operations.map(op => op.resultJson.contains("\\\"note\\\":\\\"none\\\"")).mkString(","))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nDerived posted.\n1\ntrue\ntrue"
    val first = requests.peek()
    first should include(""""name":"post_transaction"""")
    first should include(""""amount":{"type":"integer"}""")
    first should include(""""memo":{"type":"string"}""")
    first should include(""""tags":{"type":"array","items":{"type":"string"}}""")
    first should include(""""approved":{"type":"boolean"}""")
    first should include(""""note":{"anyOf":[{"type":"string"},{"type":"null"}]}""")
    first should include(""""required":["amount","memo","tags","approved"]""")

  test("explicit agentTool schema remains authoritative fallback"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, RunOptions, agentTool, runAgent, toolOk, objectSchema](std/agent.ssc)
         |[jsonValue, jStr, jField, jObj, jArr](std/json.ssc)
         |
         |```scala
         |val schema = objectSchema(
         |  jObj(List(jField("mode", jObj(List(
         |    jField("type", jStr("string")),
         |    jField("enum", jArr(List(jStr("fast"), jStr("safe"))))
         |  ))))),
         |  List("mode")
         |)
         |
         |val t = agentTool("post_transaction_explicit", "Post explicitly.", schema) { argsJson =>
         |  val args = jsonValue(argsJson)
         |  toolOk(jObj(List(jField("mode", jStr(args.get("mode").asString)))))
         |}
         |
         |val result = runAgent(
         |  AgentEndpoint("http://localhost:$port"),
         |  "explicit-schema-model",
         |  "system",
         |  "post",
         |  List(t),
         |  RunOptions(maxSteps = 4)
         |)
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nExplicit posted.\n1"
    val first = requests.peek()
    first should include(""""name":"post_transaction_explicit"""")
    first should include(""""enum":["fast","safe"]""")

  test("unsupported derived field types produce field/type diagnostic"):
    val ex = intercept[Exception]:
      run(
        """
          |[AgentSchema](std/agent.ssc)
          |
          |```scala
          |case class BadToolInput(payload: Any) derives AgentSchema
          |val schema = summon[AgentSchema[BadToolInput]]
          |println(schema.parametersJson)
          |```
          |""".stripMargin
      )

    ex.getMessage should include("std.agent AgentSchema unsupported field 'payload' type 'Any'")

  test("rozum-agent-schema-derived example runs end-to-end"):
    val src = os.read(TestPaths.repoRoot / "examples" / "rozum-agent-schema-derived.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse(src))
    ps.flush()
    buf.toString.trim should endWith("Done\nDerived posted.\nExplicit posted.\n2")

  private def reset(): Unit =
    requests.clear()
    count.set(0)

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Agent Schema Derivation Test\n\n" + source))
    ps.flush()
    buf.toString.trim
