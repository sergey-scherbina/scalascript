package scalascript.compiler.plugin.mcp

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.mcp.*
import scalascript.parser.Parser

/** WHY THIS FILE EXISTS.
 *
 *  `specs/mcp-2026-07-28.md` §8.8 recorded a gap in its own evidence: the frame
 *  between a `.ssc` closure and an MCP intrinsic was proven by READING, not by
 *  measuring. The argument was that `Interpreter.invoke` installs no catch-all
 *  and the control signal is an ordinary `RuntimeException` on a path that
 *  already propagates `PluginError.raise`. That is an argument. It was written
 *  down as one, and then three more phases were built on top of it — five
 *  intrinsics deep by the time anyone noticed, with `asTask`, `setMrtrMode` and
 *  `clientSupportsTasks` carrying ZERO coverage from `.ssc`.
 *
 *  These cases run a REAL `.ssc` program through the REAL interpreter and then
 *  drive the resulting server with a real request. Nothing here is a double:
 *  the tool handler is `.ssc` source, `invokeCallback` is the interpreter's,
 *  and the frame under test is the one that ships.
 *
 *  The builder is reachable because `mcpServer { … }` parks it in
 *  `Mcp.builderTL` and does not clear it — the same handle `serveMcp` picks up.
 *  That is load-bearing for the harness, so it is asserted rather than assumed. */
class McpSscApiTest extends AnyFunSuite with Matchers:

  /** Run `.ssc` source and hand back the server it configured. */
  private def serverFrom(body: String): McpServerBuilder =
    Mcp.builderTL.remove()
    val ps = new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true)
    Interpreter(ps).run(Parser.parse(
      s"""# Test
         |
         |```scalascript
         |enum Content:
         |  case Text(text: String)
         |
         |case class ToolResult(content: List[Content], isError: Boolean = false)
         |
         |$body
         |```
         |""".stripMargin))
    val b = Mcp.builderTL.get
    assert(b != null, "mcpServer did not leave a builder behind — the harness is broken, not the code")
    b

  private def meta(extensions: Boolean): ujson.Obj =
    val caps = if extensions then
      ujson.Obj("extensions" -> ujson.Obj(McpProtocol.TasksExtension -> ujson.Obj()))
    else ujson.Obj()
    ujson.Obj(
      McpProtocol.MetaKey.ProtocolVersion    -> McpProtocol.ModernProtocolVersion,
      McpProtocol.MetaKey.ClientCapabilities -> caps)

  private def call(b: McpServerBuilder, tasks: Boolean = true,
                   extra: (String, ujson.Value)*): ujson.Value =
    val params = ujson.Obj("name" -> "t", "arguments" -> ujson.Obj(), "_meta" -> meta(tasks))
    extra.foreach((k, v) => params(k) = v)
    ujson.read(McpServerCore.handleHttpRequest(b, ujson.Obj(
      "jsonrpc" -> "2.0", "id" -> 1, "method" -> McpProtocol.Method.ToolsCall,
      "params"  -> params).render(), "srv", "9.9.9",
      Map(McpProtocol.Header.ProtocolVersion -> McpProtocol.ModernProtocolVersion,
          McpProtocol.Header.Method          -> McpProtocol.Method.ToolsCall,
          McpProtocol.Header.Name            -> "t")).trim)

  // ── the frame §8.8 could only argue about ───────────────────────────────

  test("MRTR: a .ssc handler's elicit reaches the dispatcher as input-required"):
    // The interpreter frame carries a control-flow exception out of a .ssc
    // closure without swallowing it. That was the argued claim; this measures it.
    val b = serverFrom(
      """mcpServer { srv =>
        |  srv.tool("t") { args =>
        |    srv.elicit("approve?", Map())
        |    ToolResult(List(Content.Text("done")))
        |  }
        |}
        |""".stripMargin)
    b.setMrtrMode(MrtrMode.Replay)
    val js = call(b, tasks = false)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    js("result")("inputRequests").obj.keySet shouldBe Set("elicit-1")

  test("PARK: a .ssc handler stops and resumes, and its code above the question runs ONCE"):
    val b = serverFrom(
      """var entries = 0
        |mcpServer { srv =>
        |  srv.tool("t") { args =>
        |    entries = entries + 1
        |    val a = srv.elicit("approve?", Map())
        |    ToolResult(List(Content.Text("entries=" + entries.toString)))
        |  }
        |}
        |""".stripMargin)
    val asked = call(b, tasks = false)
    asked("result")("resultType").str shouldBe McpProtocol.ResultTypeInputRequired
    val done = call(b, tasks = false,
      "inputResponses" -> ujson.Obj("elicit-1" ->
        ujson.Obj("action" -> "accept", "content" -> ujson.Str("yes"))),
      "requestState"   -> ujson.Str(asked("result")("requestState").str))
    done("result")("resultType").str shouldBe McpProtocol.ResultTypeComplete
    // The counter lives in the .ssc program. Under replay it would read 2.
    done("result")("content")(0)("text").str shouldBe "entries=1"

  // ── the three intrinsics that had no coverage at all ────────────────────

  test("srv.asTask() from .ssc hands the caller a task handle"):
    val b = serverFrom(
      """mcpServer { srv =>
        |  srv.tool("t") { args =>
        |    srv.asTask()
        |    ToolResult(List(Content.Text("finished")))
        |  }
        |}
        |""".stripMargin)
    val js = call(b)
    js("result")("resultType").str shouldBe McpProtocol.ResultTypeTask
    js("result")("taskId").str     should not be empty

  test("srv.clientSupportsTasks() from .ssc reads THIS request's capabilities"):
    // Both answers from one program, so the case cannot pass by always
    // returning the same thing.
    val b = serverFrom(
      """mcpServer { srv =>
        |  srv.tool("t") { args =>
        |    ToolResult(List(Content.Text("supports=" + srv.clientSupportsTasks().toString)))
        |  }
        |}
        |""".stripMargin)
    call(b, tasks = true)("result")("content")(0)("text").str  shouldBe "supports=true"
    call(b, tasks = false)("result")("content")(0)("text").str shouldBe "supports=false"

  test("srv.setMrtrMode from .ssc changes what an unanswered elicit does"):
    // A COMPARISON, not a one-sided assertion: the same program under two modes,
    // differing only in the setMrtrMode line. Asserted through what a client can
    // see rather than through a private field — under `park` the result carries
    // a requestState naming the parked handler, under `replay` there is nothing
    // to name, because nothing was kept.
    def program(mode: String) = serverFrom(
      s"""mcpServer { srv =>
         |  $mode
         |  srv.tool("t") { args =>
         |    srv.elicit("approve?", Map())
         |    ToolResult(List(Content.Text("done")))
         |  }
         |}
         |""".stripMargin)

    val parked   = call(program(""), tasks = false)("result")
    val replayed = call(program("""srv.setMrtrMode("replay")"""), tasks = false)("result")

    parked("resultType").str   shouldBe McpProtocol.ResultTypeInputRequired
    replayed("resultType").str shouldBe McpProtocol.ResultTypeInputRequired

    McpProtocol.parkToken(Some(parked("requestState").str)) should not be empty
    replayed.obj.keySet should not contain "requestState"

  test("srv.setMrtrMode refuses a name it does not know, rather than defaulting"):
    // A typo that quietly selected a mode WITH a precondition is the worst
    // failure available here, so the intrinsic raises.
    a [Throwable] should be thrownBy serverFrom(
      """mcpServer { srv =>
        |  srv.setMrtrMode("prak")
        |}
        |""".stripMargin)

  test("srv.setRequestState / srv.requestState round-trip through .ssc"):
    val b = serverFrom(
      """mcpServer { srv =>
        |  srv.tool("t") { args =>
        |    val seen = srv.requestState()
        |    srv.setRequestState("row-written")
        |    srv.elicit("approve?", Map())
        |    ToolResult(List(Content.Text("seen=" + seen.toString)))
        |  }
        |}
        |""".stripMargin)
    b.setMrtrMode(MrtrMode.Replay)
    val first = call(b, tasks = false)
    McpProtocol.authorState(Some(first("result")("requestState").str)) shouldBe
      Some("row-written")
