package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** v1.17.x — Cancellation: `notifications/cancelled` with `requestId`
 *  flips the in-flight cancel flag for the matching tool/resource/prompt
 *  invocation.  Handler polls `builder.isCancelled` at safe points and
 *  bails out cooperatively. */
class McpCancellationTest extends AnyFunSuite with Matchers:

  test("notifications/cancelled flips inflight flag mid-tool"):
    val builder = new McpServerBuilder
    val started = new CountDownLatch(1)
    val cancelObserved = new java.util.concurrent.atomic.AtomicBoolean(false)
    builder.tool("long_task", None, ujson.Obj(), { _ =>
      started.countDown()
      // Poll the cancel flag for up to 2s; bail early once flipped.
      val deadline = System.currentTimeMillis() + 2_000L
      while !builder.isCancelled && System.currentTimeMillis() < deadline do
        Thread.sleep(10)
      if builder.isCancelled then cancelObserved.set(true)
      ToolHandlerResult(List(McpProtocol.textContent("done")), isError = builder.isCancelled)
    })

    // Run the tool on a background thread so we can fire the cancel
    // notification while it's polling.
    val toolThread = new Thread((() => {
      McpServerCore.dispatch(builder, McpProtocol.Method.ToolsCall,
        ujson.Obj("name" -> "long_task", "arguments" -> ujson.Obj()),
        ujson.Num(42))
      ()
    }): Runnable, "test-tool-thread")
    toolThread.setDaemon(true); toolThread.start()

    started.await(1_000L, TimeUnit.MILLISECONDS) shouldBe true
    // Fire the cancel notification while the tool is still running.
    val cancelFrame = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":42}}"""
    McpServerCore.handleHttpRequest(builder, cancelFrame)

    toolThread.join(2_500L)
    toolThread.isAlive shouldBe false
    cancelObserved.get() shouldBe true

  test("isCancelled returns false outside of a handler"):
    val builder = new McpServerBuilder
    builder.isCancelled shouldBe false

  test("cancel for unknown id is silently ignored"):
    val builder = new McpServerBuilder
    // No tool invocation in flight — cancelling id=99 is a no-op (no exception).
    val frame = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":99}}"""
    val reply = McpServerCore.handleHttpRequest(builder, frame)
    reply shouldBe ""

  test("cancel notification has no response (notification semantics)"):
    val builder = new McpServerBuilder
    val frame = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"""
    val reply = McpServerCore.handleHttpRequest(builder, frame)
    reply shouldBe ""  // notifications never get a response back
