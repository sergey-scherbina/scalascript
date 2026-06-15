package scalascript

import com.sun.net.httpserver.HttpExchange
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

class AgentEndpointPoolInterpreterTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val port = 19699
  private var server: HttpServer = scala.compiletime.uninitialized
  private val paths = ConcurrentLinkedQueue[String]()
  private val bodies = ConcurrentLinkedQueue[String]()

  override def beforeAll(): Unit =
    server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/", exchange =>
      val path = exchange.getRequestURI.getPath
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      paths.add(path)
      bodies.add(body)

      if body.contains(""""model":"pool-fallback-model"""") && path.startsWith("/primary/") then
        writeText(exchange, 503, "primary down")
      else if body.contains(""""model":"pool-fallback-model"""") && path.startsWith("/secondary/") then
        writeJson(exchange, 200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Secondary answered."}}]}""")
      else if body.contains(""""model":"pool-max-one-model"""") then
        writeText(exchange, 503, "still down")
      else if body.contains(""""model":"pool-4xx-model"""") && path.startsWith("/primary/") then
        writeText(exchange, 400, "bad request")
      else if body.contains(""""model":"pool-4xx-model"""") && path.startsWith("/secondary/") then
        writeJson(exchange, 200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"should not retry"}}]}""")
      else if body.contains(""""model":"pool-unknown-tool-model"""") && body.contains(""""role":"tool"""") then
        writeJson(exchange, 200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Recovered from pool tool error."}}]}""")
      else if body.contains(""""model":"pool-unknown-tool-model"""") then
        writeJson(exchange, 200, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_missing","type":"function","function":{"name":"missing_tool","arguments":"{}"}}]}}]}""")
      else if body.contains(""""model":"pool-single-model"""") then
        writeJson(exchange, 200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Single endpoint."}}]}""")
      else if body.contains(""""model":"pool-stream-fallback-model"""") && path.startsWith("/stream-primary/") then
        writeText(exchange, 503, "stream primary down")
      else if body.contains(""""model":"pool-stream-fallback-model"""") && path.startsWith("/stream-secondary/") then
        writeSse(exchange, List(
          sse("""{"choices":[{"delta":{"content":"Stream "},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{"content":"secondary."},"finish_reason":null}]}"""),
          sse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
          done
        ))
      else
        writeText(exchange, 500, "unexpected request: " + path)
    )
    server.setExecutor(null)
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("runAgentPool falls through 5xx endpoints in order"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(
         |  AgentEndpoint("http://localhost:$port/primary"),
         |  AgentEndpoint("http://localhost:$port/secondary")
         |), 2)
         |val result = runAgentPool(pool, "pool-fallback-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nSecondary answered.\n0"
    paths.toArray(new Array[String](0)).toList shouldBe List(
      "/primary/v1/chat/completions",
      "/secondary/v1/chat/completions"
    )

  test("runAgentPool does not retry 4xx responses"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(
         |  AgentEndpoint("http://localhost:$port/primary"),
         |  AgentEndpoint("http://localhost:$port/secondary")
         |), 2)
         |val result = runAgentPool(pool, "pool-4xx-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(result.stop)
         |println(result.text.contains("HTTP 400"))
         |```
         |""".stripMargin
    )

    out shouldBe "Error\ntrue"
    paths.toArray(new Array[String](0)).toList shouldBe List("/primary/v1/chat/completions")

  test("runAgentPool respects maxAttempts"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(
         |  AgentEndpoint("http://localhost:$port/primary"),
         |  AgentEndpoint("http://localhost:$port/secondary")
         |), 1)
         |val result = runAgentPool(pool, "pool-max-one-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(result.stop)
         |println(result.text.contains("HTTP 503"))
         |```
         |""".stripMargin
    )

    out shouldBe "Error\ntrue"
    paths.toArray(new Array[String](0)).toList shouldBe List("/primary/v1/chat/completions")

  test("runAgentPool does not retry model tool errors"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(
         |  AgentEndpoint("http://localhost:$port/primary"),
         |  AgentEndpoint("http://localhost:$port/secondary")
         |), 2)
         |val result = runAgentPool(pool, "pool-unknown-tool-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |println(result.operations.map(op => op.tool + ":" + op.isError).mkString(","))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nRecovered from pool tool error.\n1\nmissing_tool:true"
    paths.toArray(new Array[String](0)).toList shouldBe List(
      "/primary/v1/chat/completions",
      "/primary/v1/chat/completions"
    )
    bodies.toArray(new Array[String](0)).last should include("unknown tool: missing_tool")

  test("single-endpoint pool keeps stable behavior"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, runAgentPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(AgentEndpoint("http://localhost:$port/single")), 3)
         |val result = runAgentPool(pool, "pool-single-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(result.stop)
         |println(result.text)
         |println(result.operations.length)
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nSingle endpoint.\n0"
    paths.toArray(new Array[String](0)).toList shouldBe List("/single/v1/chat/completions")

  test("collectAgentStreamPool falls through 5xx endpoints in order"):
    reset()

    val out = run(
      s"""
         |[AgentEndpoint, AgentEndpointPool, RunOptions, collectAgentStreamPool](std/agent.ssc)
         |
         |```scala
         |val pool = AgentEndpointPool(List(
         |  AgentEndpoint("http://localhost:$port/stream-primary"),
         |  AgentEndpoint("http://localhost:$port/stream-secondary")
         |), 2)
         |val streamed = collectAgentStreamPool(pool, "pool-stream-fallback-model", "system", "user", List(), RunOptions(maxSteps = 4))
         |println(streamed.result.stop)
         |println(streamed.result.text)
         |println(streamed.events.map(e => e.kind + ":" + e.text + e.stop).mkString("|"))
         |```
         |""".stripMargin
    )

    out shouldBe "Done\nStream secondary.\nTextDelta:Stream |TextDelta:secondary.|Stopped:Done"
    paths.toArray(new Array[String](0)).toList shouldBe List(
      "/stream-primary/v1/chat/completions",
      "/stream-secondary/v1/chat/completions"
    )

  test("rozum-agent-pool example runs end-to-end with fake primary and secondary gateways"):
    val src = os.read(TestPaths.repoRoot / "examples" / "rozum-agent-pool.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse(src))
    ps.flush()
    buf.toString.trim should endWith("Done\nSecondary gateway answered.\n0\n1\n1")

  private def writeJson(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()

  private def writeText(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "text/plain")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()

  private def writeSse(exchange: HttpExchange, frames: List[String]): Unit =
    exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
    val os = exchange.getResponseBody
    frames.foreach { frame =>
      os.write(frame.getBytes(StandardCharsets.UTF_8))
      os.flush()
    }
    os.close()

  private def sse(data: String): String =
    s"data: $data\n\n"

  private def done: String =
    "data: [DONE]\n\n"

  private def reset(): Unit =
    paths.clear()
    bodies.clear()

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Endpoint Pool Test\n\n" + source))
    ps.flush()
    buf.toString.trim
