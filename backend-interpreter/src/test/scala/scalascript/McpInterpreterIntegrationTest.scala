package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

/** Smoke test: a `.ssc` source that imports `std/mcp/server.ssc` and
 *  calls `mcpServer { srv => srv.tool(...)}` runs to completion under
 *  the interpreter (no "feature not supported" error, no runtime crash
 *  on the registration step).
 *
 *  We exercise the setup block but stop short of calling `serveMcp(...)` —
 *  the stdio loop would block on `System.in.readLine()` and never return. */
class McpInterpreterIntegrationTest extends AnyFunSuite with Matchers:

  private def run(src: String): String =
    val buf = new java.io.ByteArrayOutputStream()
    val ps  = new java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("mcpServer setup block runs without error (no SDK / no Feature-not-supported)"):
    // Stay inline — don't depend on resolving std/mcp/*.ssc from the test
    // working dir.  The intrinsics are registered globally so they're
    // callable from any source.  We provide the case-class shapes the
    // tool result uses (ToolResult / Content.Text) inline.
    val src =
      """# Test
        |
        |```scalascript
        |enum Content:
        |  case Text(text: String)
        |
        |case class ToolResult(content: List[Content], isError: Boolean = false)
        |
        |mcpServer { srv =>
        |  srv.tool("echo") { args =>
        |    val msg = args.getOrElse("msg", "")
        |    ToolResult(List(Content.Text(msg.toString)))
        |  }
        |}
        |println("setup-ok")
        |```
        |""".stripMargin
    run(src) shouldBe "setup-ok"

  test("mcpConnect with Stdio transport raises InterpretError pointing at Spawn"):
    val src =
      """# Test
        |
        |```scalascript
        |enum Transport:
        |  case Stdio
        |
        |val client = mcpConnect(Transport.Stdio)
        |```
        |""".stripMargin
    val ex = intercept[InterpretError] { run(src) }
    ex.getMessage should include ("Spawn")

  test("mcpConnect: unknown transport raises a clear error"):
    val src =
      """# Test
        |
        |```scalascript
        |enum Transport:
        |  case Unknown
        |
        |val client = mcpConnect(Transport.Unknown)
        |```
        |""".stripMargin
    val ex = intercept[InterpretError] { run(src) }
    ex.getMessage should include ("unsupported transport")
