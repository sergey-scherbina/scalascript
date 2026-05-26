package scalascript.compiler.plugin.mcp

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class McpPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(McpInterpreterPlugin()))

  test("MCP plugin builds server handles and registers handlers in isolation"):
    val result = interp.eval(
      """
      var observed = List()

      mcpServer { srv =>
        val authBefore = srv.authEnabled()
        val pageBefore = srv.currentPageSize()

        srv.tool("echo") { args => "ok" }
        srv.resource("mem://item", "item", "text/plain") { uri => "body:" + uri }
        srv.prompt("hello", "Greeting prompt") { args => "hello" }

        srv.notifyToolsListChanged()
        srv.notifyResourcesListChanged()
        srv.notifyPromptsListChanged()
        srv.notifyResourceUpdate("mem://item")
        srv.notify("notifications/test", Map("ok" -> true))

        srv.setPageSize(7)
        srv.useHmacValidator("secret")
        val token = srv.issueHmacToken("secret", "alice", List("read"), 60)

        observed = List(
          authBefore,
          srv.authEnabled(),
          pageBefore,
          srv.currentPageSize(),
          token.length > 20,
          srv.currentLogLevel(),
          srv.currentAuth().isEmpty,
          srv.isCancelled(),
          srv.clientSupportsRoots(),
          srv.clientSupportsElicitation()
        )
      }

      observed
      """
    )

    assert(result == List(false, true, 0L, 7L, true, "info", true, false, false, false))

  test("MCP plugin exposes server completion and auth configuration helpers in isolation"):
    val result = interp.eval(
      """
      var observed = List()

      mcpServer { srv =>
        srv.setAuthRealm("tools")
        srv.setProtectedResourceMetadata(Map(
          "resource" -> "https://api.example/mcp",
          "authorization_servers" -> List("https://auth.example")
        ))
        srv.completionForPrompt("search", "query", prefix => List(prefix + "-a", prefix + "-b"))
        srv.completionForResource("file:///{path}", "path", prefix => List(prefix + ".txt"))

        observed = List(srv.authEnabled(), srv.currentPageSize(), srv.currentLogLevel())
      }

      observed
      """
    )

    assert(result == List(false, 0L, "info"))
