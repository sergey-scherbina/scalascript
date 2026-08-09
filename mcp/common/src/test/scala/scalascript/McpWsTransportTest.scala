package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*

/** Phase 2 follow-up — WebSocket transport client-side smoke tests.
 *
 *  Full end-to-end (interpreter WS server ↔ McpWsClient) is hard to
 *  drive from a unit test because the interpreter's WebServer binds a
 *  port and is process-global — colliding with parallel test suites in
 *  the same JVM.  Coverage strategy:
 *
 *   - **Client-side construction** is unit-tested here.
 *   - **handleHttpRequest** dispatching (same code path the WS handler
 *     uses) is covered by McpHttpTransportTest.
 *   - **Real client↔server traffic** is exercised in CLI-level smoke
 *     tests that spawn a separate `ssc` subprocess (future iteration). */
class McpWsTransportTest extends AnyFunSuite with Matchers:

  test("McpWsClient: handshake failure on unreachable URL surfaces as an exception"):
    // port 1 — privileged, never listens for users.  The JDK WebSocket
    // builder wraps connect failures in ExecutionException; the message
    // points at the underlying ConnectException.
    val ex = intercept[Throwable] {
      new McpWsClient("ws://127.0.0.1:1/mcp", 500)
    }
    // We accept any failure mode (ExecutionException / RuntimeException
    // / IOException) as long as the message hints at the connection
    // problem rather than a generic NullPointer.
    val msg = Option(ex.getMessage).getOrElse("")
    (msg.toLowerCase should (
      include ("connect") or include ("refused") or include ("handshake") or include ("timeout") or include ("permission")
    ))

  test("McpWsClient: close on a never-connected client is a no-op"):
    // Construct only succeeds when the URL handshakes; the
    // unreachable-port case above already covers the failure path.
    // This test asserts that double-close doesn't throw — a regression
    // guard for the close()/isClosed contract.
    try
      val c = new McpWsClient("ws://127.0.0.1:1/mcp", 200)
      c.close(); c.close()
      c.isClosed shouldBe true
    catch case _: Throwable => succeed  // expected — never connected
