package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
import scalascript.interpreter.debug.DebugFrame
import scalascript.parser.Parser
import java.util.concurrent.TimeUnit

/** Unit tests for [[ReplDebugHooks]]: breakpoints, step modes, locals, and
 *  `:print`-style expression evaluation.  No stdin simulation needed — we
 *  drive the hooks directly from the test thread. */
class ReplDebugTest extends AnyFunSuite:

  private def makeInterp(hooks: ReplDebugHooks): Interpreter =
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))
    interp.setDebugSourceFile("<repl>")
    interp.setDebugHooks(Some(hooks.mkHooks()))
    interp

  private def startSnippet(interp: Interpreter, hooks: ReplDebugHooks, code: String): Thread =
    hooks.resetForNewSnippet()
    Thread.ofVirtual().start { () =>
      try   interp.runSnippet(code)
      catch case _: Throwable => ()
      finally hooks.signalFinished()
    }

  /** Poll the queue; returns None = execution finished, Some(frame) = stopped.
   *  Fails the test if nothing arrives within 5 s. */
  private def poll(hooks: ReplDebugHooks): Option[DebugFrame] =
    val item = hooks.stoppedQueue.poll(5, TimeUnit.SECONDS)
    assert(item != null, "timed out waiting for stopped/finished event")
    item

  test("breakpoint stops at correct snippet line"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(2)
    val thread = startSnippet(interp, hooks, "val x = 1\nval y = 2\nval z = 3")

    val frame = poll(hooks)
    assert(frame.isDefined, "expected a stop event")
    val snippetLine = frame.get.line - hooks.blockDocLine
    assert(snippetLine == 2)

    hooks.resume()
    assert(poll(hooks).isEmpty, "expected finished signal after continue")
    thread.join(5000)

  test("breakpoint populates locals in stopped frame"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(2)
    val thread = startSnippet(interp, hooks, "val x = 42\nval y = x + 1")

    val frame = poll(hooks).get
    assert(frame.locals.get("x").contains(Value.IntV(42)))

    hooks.resume()
    poll(hooks)
    thread.join(5000)

  test("step-in stops at first then second snippet line"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.enableStepIn()
    val thread = startSnippet(interp, hooks, "val a = 1\nval b = 2")

    val frame1 = poll(hooks).get
    assert(frame1.line - hooks.blockDocLine == 1)

    hooks.resume(ReplDebugHooks.StepMode.StepIn)
    val frame2 = poll(hooks).get
    assert(frame2.line - hooks.blockDocLine == 2)

    hooks.resume()
    assert(poll(hooks).isEmpty)
    thread.join(5000)

  test("continue proceeds to next breakpoint then finishes"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(1)
    hooks.setBreakpoint(2)
    val thread = startSnippet(interp, hooks, "val x = 1\nval y = 2\nval z = 3")

    val frame1 = poll(hooks).get
    assert(frame1.line - hooks.blockDocLine == 1)

    hooks.resume(ReplDebugHooks.StepMode.Off)   // :continue → runs to next breakpoint

    val frame2 = poll(hooks).get
    assert(frame2.line - hooks.blockDocLine == 2, "expected stop at line 2")

    hooks.resume()                               // :continue → runs to end
    assert(poll(hooks).isEmpty, "expected finished after second continue")
    thread.join(5000)

  test("step-over stops at next line at same call depth"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(1)
    val thread = startSnippet(interp, hooks, "val x = 1\nval y = 2")

    val frame1 = poll(hooks).get
    assert(frame1.line - hooks.blockDocLine == 1)
    hooks.resume(ReplDebugHooks.StepMode.StepOver(frame1.callDepth))

    val frame2 = poll(hooks).get
    assert(frame2.line - hooks.blockDocLine == 2)
    hooks.resume()
    assert(poll(hooks).isEmpty)
    thread.join(5000)

  test("clearAllBreakpoints removes all stops"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(1)
    hooks.setBreakpoint(2)
    hooks.clearAllBreakpoints()
    val thread = startSnippet(interp, hooks, "val x = 1\nval y = 2")

    assert(poll(hooks).isEmpty, "expected finished with no stops")
    thread.join(5000)

  test("evalExpr evaluates in current frame context"):
    val hooks  = ReplDebugHooks()
    val interp = makeInterp(hooks)
    hooks.setBreakpoint(2)
    val thread = startSnippet(interp, hooks, "val n = 10\nval m = n * 2")

    val frame = poll(hooks).get
    val result = interp.evalExpr("n + 1", frame.locals)
    assert(result == Value.IntV(11))

    hooks.resume()
    poll(hooks)
    thread.join(5000)
