package scalascript.dap

import ujson.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

/** Handles one DAP client connection. Phase 2: breakpoints + stopped event.
 *
 *  Threading model:
 *  - The DAP message loop runs on the caller's thread (inside [[run]]).
 *  - The interpreter thread may call [[suspendUntilResume]] (via [[mkHooks]])
 *    from a different thread.
 *  - All writes to the socket output stream are protected by [[writeLock]] so
 *    responses and events from different threads don't interleave.
 *  - [[readyLatch]] gates interpreter start: the interpreter thread calls
 *    [[awaitReady]] and blocks until `configurationDone` is processed, so all
 *    breakpoints are registered before the first line of user code executes.
 *  - [[suspendLatch]] handles per-breakpoint suspend/resume.
 */
final class DapSession(conn: Socket):
  private val in       = conn.getInputStream
  private val out      = conn.getOutputStream
  private val writeLock = Object()
  private var seq      = 0

  // Phase 2: breakpoint registry, shared between the DAP reader thread and interpreter thread.
  private val breakpoints = scalascript.interpreter.debug.BreakpointRegistry()

  // Phase 2: the interpreter blocks here until configurationDone is received.
  private val readyLatch  = CountDownLatch(1)

  // Phase 2: latch used to block the interpreter thread at a breakpoint until the client resumes.
  private val suspendLatch = AtomicReference[CountDownLatch](null)

  /** Block the interpreter thread until the client has sent all breakpoints
   *  and `configurationDone`.  Must be called before user code begins executing. */
  def awaitReady(): Unit = readyLatch.await()

  /** Called from the interpreter thread when a breakpoint is hit.
   *  Sends a `stopped` event, then blocks until the client sends `continue`. */
  private[dap] def suspendUntilResume(@annotation.unused frame: scalascript.interpreter.debug.DebugFrame): Unit =
    val latch = CountDownLatch(1)
    suspendLatch.set(latch)
    sendEvent("stopped", Obj(
      "reason"            -> Str("breakpoint"),
      "threadId"          -> Num(1),
      "allThreadsStopped" -> True,
    ))
    latch.await()

  private def resume(): Unit =
    val latch = suspendLatch.getAndSet(null)
    if latch != null then latch.countDown()

  /** Returns a DebugHooks instance that routes breakpoint hits back into this session. */
  def mkHooks(): scalascript.interpreter.debug.DebugHooks = new BreakpointHooks

  private class BreakpointHooks extends scalascript.interpreter.debug.DebugHooks:
    def isBreakpoint(sourceFile: String, line: Int): Boolean =
      breakpoints.contains(sourceFile, line)
    def onStep(frame: scalascript.interpreter.debug.DebugFrame): scalascript.interpreter.debug.StepAction =
      suspendUntilResume(frame)
      scalascript.interpreter.debug.StepAction.Stop(scalascript.interpreter.debug.StopReason.Breakpoint)
    def onOutput(category: String, msg: String): Unit = ()

  def run(): Unit =
    try
      while !conn.isClosed do
        val msg = DapProtocol.readMessage(in)
        handleMessage(msg)
    catch case _: java.io.EOFException | _: java.io.IOException => ()
    finally
      // Unblock any waiting interpreter thread so it can terminate cleanly.
      readyLatch.countDown()
      resume()
      conn.close()

  private def handleMessage(msg: Value): Unit =
    val msgType = msg("type").str
    if msgType == "request" then
      val cmd    = msg("command").str
      val reqSeq = msg("seq").num.toInt
      cmd match
        case "initialize"        => handleInitialize(reqSeq, msg)
        case "launch"            => handleLaunch(reqSeq, msg)
        case "configurationDone" =>
          sendResponse(reqSeq, "configurationDone", Obj())
          // Unblock the interpreter: all breakpoints have been set.
          readyLatch.countDown()
        case "setBreakpoints"    => handleSetBreakpoints(reqSeq, msg)
        case "continue"          =>
          resume()
          sendResponse(reqSeq, "continue", Obj("allThreadsContinued" -> True))
        case "threads"           =>
          sendResponse(reqSeq, "threads", Obj(
            "threads" -> Arr(Obj("id" -> Num(1), "name" -> Str("main")))
          ))
        case "disconnect"        => handleDisconnect(reqSeq, msg)
        case other               => sendResponse(reqSeq, other, Obj(), success = false, message = s"unknown command: $other")

  private def handleInitialize(reqSeq: Int, @annotation.unused msg: Value): Unit =
    val caps = Obj(
      "supportsConfigurationDoneRequest" -> True,
      "supportTerminateDebuggee"         -> True,
    )
    sendResponse(reqSeq, "initialize", caps)
    sendEvent("initialized", Obj())

  private def handleLaunch(reqSeq: Int, @annotation.unused msg: Value): Unit =
    sendResponse(reqSeq, "launch", Obj())

  private def handleSetBreakpoints(reqSeq: Int, msg: Value): Unit =
    val args  = msg("arguments")
    val src   = args("source")("path").str
    val bpArr = args("breakpoints").arr
    val lines = bpArr.map(_("line").num.toInt).toSet
    breakpoints.setBreakpoints(src, lines)
    val verified = lines.toSeq.map(l => Obj("line" -> Num(l), "verified" -> True))
    sendResponse(reqSeq, "setBreakpoints", Obj("breakpoints" -> Arr(verified*)))

  private def handleDisconnect(reqSeq: Int, @annotation.unused msg: Value): Unit =
    // Resume any blocked interpreter thread so it can terminate cleanly.
    resume()
    sendResponse(reqSeq, "disconnect", Obj())
    sendEvent("terminated", Obj())
    conn.close()

  private[dap] def sendResponse(reqSeq: Int, command: String, body: Value,
                         success: Boolean = true, message: String = ""): Unit =
    writeLock.synchronized {
      seq += 1
      val resp = Obj(
        "seq"         -> Num(seq),
        "type"        -> Str("response"),
        "request_seq" -> Num(reqSeq),
        "success"     -> (if success then True else False),
        "command"     -> Str(command),
        "body"        -> body,
      )
      if message.nonEmpty then resp("message") = Str(message)
      DapProtocol.writeMessage(out, resp)
    }

  private[dap] def sendEvent(event: String, body: Value): Unit =
    writeLock.synchronized {
      seq += 1
      val evt = Obj(
        "seq"   -> Num(seq),
        "type"  -> Str("event"),
        "event" -> Str(event),
        "body"  -> body,
      )
      DapProtocol.writeMessage(out, evt)
    }
