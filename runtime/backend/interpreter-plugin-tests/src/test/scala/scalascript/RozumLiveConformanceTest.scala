package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.compiler.plugin.http.HttpInterpreterPlugin
import scalascript.compiler.plugin.json.JsonInterpreterPlugin
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class RozumLiveConformanceTest extends AnyFunSuite with Matchers:

  test("std.agent speaks a live rozum OpenAI-compatible non-streaming gateway"):
    val baseUrl = requiredEnv("ROZUM_BASE_URL")
    val model = requiredEnv("ROZUM_MODEL")
    val token = sys.env.getOrElse("ROZUM_AUTH_TOKEN", "")

    val out = run(liveSource(stripTrailingSlash(baseUrl), model, token))

    out shouldBe "Done\ntrue\n0\ntrue\ntrue"

  private def requiredEnv(name: String): String =
    sys.env.get(name).filter(_.nonEmpty).getOrElse:
      cancel(s"$name not set - opt in with ROZUM_BASE_URL and ROZUM_MODEL to hit a live rozum gateway")

  private def stripTrailingSlash(value: String): String =
    value.replaceAll("/+$", "")

  private def liveSource(baseUrl: String, model: String, token: String): String =
    s"""
       |[AgentEndpoint, RunOptions, runAgent](std/agent.ssc)
       |
       |```scala
       |val result = runAgent(
       |  AgentEndpoint(${sscString(baseUrl)}, ${sscString(token)}),
       |  ${sscString(model)},
       |  "You are serving a ScalaScript to rozum transport conformance smoke.",
       |  "Return one short acknowledgement for a non-streaming gateway smoke test.",
       |  List(),
       |  RunOptions(temperature = 0.0, maxSteps = 2, maxTokens = 32)
       |)
       |
       |println(result.stop)
       |println(result.text.trim.length > 0)
       |println(result.operations.length)
       |println(result.transcriptJson.contains("\\\"role\\\":\\\"system\\\""))
       |println(result.transcriptJson.contains("\\\"role\\\":\\\"user\\\""))
       |```
       |""".stripMargin

  private def sscString(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    } + "\""

  private def run(source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(TestPaths.repoRoot))
    interp.installPlugins(List(HttpInterpreterPlugin(), JsonInterpreterPlugin()))
    interp.run(Parser.parse("# Live Rozum Conformance\n\n" + source))
    ps.flush()
    buf.toString.trim
